package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import com.thanhdatpb.listingguard.rag.policy.ParsedPolicy;
import com.thanhdatpb.listingguard.rag.policy.PolicyChunk;
import com.thanhdatpb.listingguard.rag.policy.PolicyChunker;
import com.thanhdatpb.listingguard.rag.policy.PolicyManifest;
import com.thanhdatpb.listingguard.rag.policy.PolicyManifestLoader;
import com.thanhdatpb.listingguard.rag.policy.PolicyParseException;
import com.thanhdatpb.listingguard.rag.policy.PolicyParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the policy corpus from disk into the database. Reads only local files (no network except the
 * embedding backend), and is idempotent: a document whose file, manifest entry and pipeline are unchanged
 * is skipped, a changed document gets its chunks replaced, and a document removed from the manifest is deleted.
 */
@Service
public class PolicyIngestService {

    private static final Logger log = LoggerFactory.getLogger(PolicyIngestService.class);

    /** Separates the chunking settings from the embedding model id in a stored {@code pipeline_version}. */
    static final String MODEL_SEPARATOR = "|";

    public enum Outcome {
        INGESTED,
        UNCHANGED,
        PRUNED
    }

    public record DocumentResult(String marketplace, String file, Outcome outcome, int chunks) {
    }

    public record Report(List<DocumentResult> documents) {

        public long count(Outcome outcome) {
            return documents.stream().filter(result -> result.outcome() == outcome).count();
        }
    }

    private record Pending(PolicyManifest.Entry entry, String sourceHash, List<PolicyChunk> chunks) {
    }

    private final PolicyProperties properties;
    private final EmbeddingProvider embeddings;
    private final PolicyRepository repository;
    private final TransactionTemplate transactions;

    public PolicyIngestService(PolicyProperties properties, EmbeddingProvider embeddings, PolicyRepository repository,
                               TransactionTemplate transactions) {
        this.properties = properties;
        this.embeddings = embeddings;
        this.repository = repository;
        this.transactions = transactions;
    }

    /**
     * Everything that can be checked without the embedding backend (files exist, pages parse, versions match,
     * content is not empty) is checked for all documents first, so a bad file fails the run before any change.
     */
    public Report ingest(Path policiesDir) {
        PolicyManifest manifest = PolicyManifestLoader.load(policiesDir.resolve("manifest.yaml"));
        PolicyChunker chunker = new PolicyChunker(properties.chunkerConfig());
        String pipelineVersion = pipelineVersion();

        List<DocumentResult> results = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        for (PolicyManifest.Entry entry : manifest.documents()) {
            Path file = policiesDir.resolve("raw").resolve(entry.marketplace().toLowerCase(Locale.ROOT)).resolve(entry.file());
            byte[] bytes = read(file);
            String sourceHash = sha256(bytes);

            Optional<PolicyRepository.DocumentState> stored = repository.findDocument(entry.marketplace(), entry.file());
            if (stored.isPresent() && isCurrent(stored.get(), entry, sourceHash, pipelineVersion)) {
                if (!stored.get().fetchedAt().equals(entry.fetchedAt())) {
                    repository.updateFetchedAt(stored.get().id(), entry.fetchedAt());
                }
                results.add(new DocumentResult(entry.marketplace(), entry.file(), Outcome.UNCHANGED, repository.countChunks(stored.get().id())));
                continue;
            }

            ParsedPolicy parsed = PolicyParser.forFile(entry.file()).parse(bytes);
            if (parsed.lastUpdated() != null && !parsed.lastUpdated().toString().equals(entry.version())) {
                throw new PolicyParseException(entry.file() + ": manifest version " + entry.version()
                        + " does not match the page's 'Last updated' date " + parsed.lastUpdated()
                        + ". Update the manifest, or save the current page.");
            }
            List<PolicyChunk> chunks = chunker.chunk(entry.title(), parsed);
            if (chunks.isEmpty()) {
                throw new PolicyParseException(entry.file() + ": no content could be extracted; is the saved page complete?");
            }
            pending.add(new Pending(entry, sourceHash, chunks));
        }

        for (Pending document : pending) {
            results.add(store(document, pipelineVersion));
        }
        results.addAll(prune(manifest));
        log.info("Policy ingest done: {} ingested, {} unchanged, {} pruned", countOf(results, Outcome.INGESTED),
                countOf(results, Outcome.UNCHANGED), countOf(results, Outcome.PRUNED));
        return new Report(results);
    }

    private DocumentResult store(Pending document, String pipelineVersion) {
        PolicyManifest.Entry entry = document.entry();
        List<float[]> vectors = embeddings.embed(document.chunks().stream().map(PolicyChunk::embeddingText).toList(),
                EmbeddingProvider.Kind.DOCUMENT);
        List<PolicyRepository.NewChunk> rows = new ArrayList<>(document.chunks().size());
        for (int i = 0; i < document.chunks().size(); i++) {
            PolicyChunk chunk = document.chunks().get(i);
            String url = chunk.anchor() == null ? entry.url() : entry.url() + "#" + chunk.anchor();
            rows.add(new PolicyRepository.NewChunk(chunk.ordinal(), chunk.sectionPath(), url, chunk.content(),
                    chunk.contentHash(), chunk.metadata(), vectors.get(i)));
        }
        transactions.executeWithoutResult(status -> {
            long id = repository.upsertDocument(entry, document.sourceHash(), pipelineVersion);
            repository.replaceChunks(id, entry.marketplace(), rows);
        });
        log.info("Ingested {} ({} chunks)", entry.file(), rows.size());
        return new DocumentResult(entry.marketplace(), entry.file(), Outcome.INGESTED, rows.size());
    }

    /** Deletes stored documents that the manifest no longer lists. A marketplace absent from the manifest is left alone. */
    private List<DocumentResult> prune(PolicyManifest manifest) {
        Set<String> marketplaces = new LinkedHashSet<>();
        manifest.documents().forEach(entry -> marketplaces.add(entry.marketplace()));
        List<DocumentResult> pruned = new ArrayList<>();
        for (String marketplace : marketplaces) {
            Set<String> listed = new LinkedHashSet<>();
            manifest.documents().stream().filter(entry -> entry.marketplace().equals(marketplace)).forEach(entry -> listed.add(entry.file()));
            for (String stored : repository.documentFiles(marketplace)) {
                if (!listed.contains(stored)) {
                    repository.deleteDocument(marketplace, stored);
                    pruned.add(new DocumentResult(marketplace, stored, Outcome.PRUNED, 0));
                    log.info("Pruned {}/{}: not in the manifest any more", marketplace, stored);
                }
            }
        }
        return pruned;
    }

    private boolean isCurrent(PolicyRepository.DocumentState stored, PolicyManifest.Entry entry, String sourceHash, String pipelineVersion) {
        return stored.sourceHash().equals(sourceHash)
                && stored.pipelineVersion().equals(pipelineVersion)
                && stored.title().equals(entry.title())
                && stored.url().equals(entry.url())
                && stored.version().equals(entry.version());
    }

    /** Anything that changes chunk boundaries or vectors changes this, so stored documents get redone. */
    String pipelineVersion() {
        PolicyProperties.Chunking chunking = properties.chunking();
        return PolicyChunker.VERSION + "|" + chunking.maxChars() + "/" + chunking.minChars() + MODEL_SEPARATOR + embeddings.modelId();
    }

    /** True when a stored {@code pipeline_version} was built with this embedding model (it always ends with it). */
    static boolean embeddedWith(String storedPipelineVersion, String modelId) {
        return storedPipelineVersion.endsWith(MODEL_SEPARATOR + modelId);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new PolicyParseException("File listed in the manifest cannot be read: " + file, e);
        }
    }

    private static long countOf(List<DocumentResult> results, Outcome outcome) {
        return results.stream().filter(result -> result.outcome() == outcome).count();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
