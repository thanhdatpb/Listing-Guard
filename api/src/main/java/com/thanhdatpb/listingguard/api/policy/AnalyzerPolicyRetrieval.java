package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.rag.analysis.AnalyzerDescriptor;
import com.thanhdatpb.listingguard.rag.analysis.AnalyzerProfile;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingException;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingNotConfiguredException;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import com.thanhdatpb.listingguard.rag.policy.PolicyChunker;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalyzerPolicyRetrieval {
    private static final String ALGORITHM = "bounded-policy-1";
    private static final Comparator<RetrievedChunk> BEST_FIRST = Comparator.comparingDouble(RetrievedChunk::score)
            .reversed().thenComparingLong(RetrievedChunk::chunkId);

    public enum Status {
        READY, UNSUPPORTED_MARKETPLACE, UNSUPPORTED_LOCALE, MISSING_CORPUS, EMPTY_CORPUS,
        STALE_CORPUS, POLICY_VERSION_MISMATCH, INVALID_PROVENANCE, NO_USABLE_EVIDENCE,
        EMBEDDING_NOT_CONFIGURED, CORPUS_MODEL_MISMATCH, CORPUS_PIPELINE_MISMATCH, EMBEDDING_FAILED, RETRIEVAL_TIMEOUT, RETRIEVAL_ERROR
    }

    public record Evidence(RetrievedChunk chunk, long documentId, String sourceHash, LocalDate fetchedAt, String sourceLocale) { }

    public record Result(Status status, AnalyzerDescriptor descriptor, List<Evidence> evidence) {
        public Result {
            evidence = List.copyOf(evidence);
        }
    }

    private record Document(long id, String file, String title, String url, String version, String hash,
                            LocalDate fetchedAt, String pipelineVersion, boolean hasChunks) { }

    private final AnalyzerProfile profile = AnalyzerProfile.etsy();
    private final JdbcTemplate jdbc;
    private final PgVectorPolicyRetriever retriever;
    private final EmbeddingProvider embeddings;
    private final Clock clock;
    private final PolicyProperties policyProperties;
    private final TransactionTemplate snapshot;

    public AnalyzerPolicyRetrieval(JdbcTemplate jdbc, PgVectorPolicyRetriever retriever, EmbeddingProvider embeddings,
                                   Clock clock, PlatformTransactionManager transactions, PolicyProperties policyProperties) {
        this.jdbc = jdbc;
        this.retriever = retriever;
        this.embeddings = embeddings;
        this.clock = clock;
        this.policyProperties = policyProperties;
        this.snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setTimeout(30);
    }

    public AnalyzerProfile profile() {
        return profile;
    }

    public Result retrieve(String marketplace, String language, Listing listing) {
        if (!profile.settings().marketplace().equalsIgnoreCase(marketplace)) {
            return incomplete(Status.UNSUPPORTED_MARKETPLACE);
        }
        String queryLanguage = language == null ? "" : language.toLowerCase(Locale.ROOT);
        if (!profile.settings().probes().containsKey(queryLanguage)) {
            return incomplete(Status.UNSUPPORTED_LOCALE);
        }
        List<String> queries = profile.queries(listing, queryLanguage);
        try {
            return snapshot.execute(ignored -> retrieveSnapshot(queries));
        } catch (EmbeddingNotConfiguredException e) {
            return incomplete(Status.EMBEDDING_NOT_CONFIGURED);
        } catch (CorpusModelMismatchException e) {
            return incomplete(Status.CORPUS_MODEL_MISMATCH);
        } catch (EmbeddingException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                    return incomplete(Status.RETRIEVAL_TIMEOUT);
                }
            }
            return incomplete(Status.EMBEDDING_FAILED);
        } catch (QueryTimeoutException | TransactionTimedOutException e) {
            return incomplete(Status.RETRIEVAL_TIMEOUT);
        } catch (DataAccessException | TransactionException e) {
            return incomplete(Status.RETRIEVAL_ERROR);
        }
    }

    private Result retrieveSnapshot(List<String> queries) {
        var corpus = loadCorpus();
        if (corpus.status() != Status.READY) {
            return incomplete(corpus.status());
        }
        var settings = profile.settings();
        var documents = corpus.documents();
        var groups = retriever.searchAll(settings.marketplace(), queries, settings.topK(), documents.stream().map(Document::id).toList());
        Map<String, Document> byFile = documents.stream().collect(Collectors.toMap(Document::file, Function.identity()));
        var ranked = groups.stream().flatMap(List::stream).sorted(BEST_FIRST).toList();
        var accepted = new ArrayList<Evidence>();
        var seen = new HashSet<Long>();
        int remaining = settings.contextChars();
        for (RetrievedChunk chunk : ranked) {
            Document document = byFile.get(chunk.sourceFile());
            if (!validChunk(chunk, document)) {
                return incomplete(Status.INVALID_PROVENANCE);
            }
            if (!seen.add(chunk.chunkId()) || accepted.size() >= settings.maxChunks()) {
                continue;
            }
            int length = chunk.content().length() + chunk.sectionPath().length() + chunk.url().length()
                    + chunk.document().length() + chunk.sourceFile().length() + chunk.version().length();
            if (length <= remaining) {
                accepted.add(new Evidence(chunk, document.id(), document.hash(), document.fetchedAt(), settings.sourceLocale()));
                remaining -= length;
            }
        }
        if (accepted.isEmpty()) {
            return incomplete(Status.NO_USABLE_EVIDENCE);
        }
        return new Result(Status.READY, corpus.descriptor(), accepted);
    }

    java.util.Optional<AnalyzerDescriptor> currentDescriptor() {
        try {
            return java.util.Optional.ofNullable(snapshot.execute(ignored -> loadCorpus().descriptor()));
        } catch (DataAccessException | TransactionException e) {
            return java.util.Optional.empty();
        }
    }

    private record Corpus(List<Document> documents, Status status, AnalyzerDescriptor descriptor) { }

    private Corpus loadCorpus() {
        jdbc.execute("SET LOCAL statement_timeout = '5s'");
        var settings = profile.settings();
        List<String> orderedFiles = settings.sources().keySet().stream().sorted().toList();
        String parameters = "?,".repeat(orderedFiles.size() - 1) + "?";
        List<Object> bindings = new ArrayList<>();
        bindings.add(settings.marketplace());
        bindings.addAll(orderedFiles);
        List<Document> documents = jdbc.query("""
                SELECT d.id, d.source_file, d.title, left(d.url, 2049) AS url, d.version, d.source_hash, d.fetched_at, d.pipeline_version,
                       EXISTS (SELECT 1 FROM policy_chunk c WHERE c.document_id = d.id) AS has_chunks
                FROM policy_document d WHERE d.marketplace_code = ? AND d.source_file IN (
                """ + parameters + ") ORDER BY d.source_file COLLATE \"C\"", (rs, row) ->
                new Document(rs.getLong("id"), rs.getString("source_file"), rs.getString("title"), rs.getString("url"),
                        rs.getString("version"), rs.getString("source_hash"), rs.getObject("fetched_at", LocalDate.class),
                        rs.getString("pipeline_version"), rs.getBoolean("has_chunks")), bindings.toArray());
        if (documents.size() != settings.sources().size()) {
            return new Corpus(List.of(), Status.MISSING_CORPUS, null);
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (Document document : documents) {
            Status status = validateDocument(document, today);
            if (status != Status.READY) {
                return new Corpus(List.of(), status, null);
            }
        }

        String pipeline = PolicyIngestService.pipelineVersion(policyProperties.chunking(), embeddings.modelId());
        for (Document document : documents) {
            if (PolicyIngestService.embeddedWith(document.pipelineVersion(), embeddings.modelId())
                    && !pipeline.equals(document.pipelineVersion())) {
                return new Corpus(List.of(), Status.CORPUS_PIPELINE_MISMATCH, null);
            }
        }
        for (Document document : documents) {
            if (!PolicyIngestService.embeddedWith(document.pipelineVersion(), embeddings.modelId())) {
                return new Corpus(List.of(), Status.CORPUS_MODEL_MISMATCH, null);
            }
        }
        String fingerprint = documents.stream().map(doc -> String.join("\t", doc.file(), doc.hash(), doc.version()))
                .collect(Collectors.joining("\n"));
        var descriptor = new AnalyzerDescriptor(settings.version(), profile.hash(),
                AnalyzerProfile.sha256(fingerprint.getBytes(StandardCharsets.UTF_8)), PolicyChunker.VERSION,
                embeddings.modelId(), pipeline, ALGORITHM);
        return new Corpus(documents, Status.READY, descriptor);
    }

    private Status validateDocument(Document doc, LocalDate today) {
        var expected = profile.settings().sources().get(doc.file());
        if (doc.id() <= 0 || doc.title().isBlank() || !doc.url().equals(expected.url())
                || !doc.hash().matches("[0-9a-f]{64}") || doc.fetchedAt().isAfter(today)) {
            return Status.INVALID_PROVENANCE;
        }
        LocalDate version;
        try {
            version = LocalDate.parse(doc.version());
        } catch (DateTimeParseException e) {
            return Status.INVALID_PROVENANCE;
        }
        LocalDate expectedVersion = LocalDate.parse(expected.version());
        if (version.isBefore(expectedVersion) || doc.fetchedAt().isBefore(today.minusDays(profile.settings().maxSnapshotAgeDays()))) {
            return Status.STALE_CORPUS;
        }
        if (!doc.version().equals(expected.version())) {
            return Status.POLICY_VERSION_MISMATCH;
        }
        if (version.isAfter(doc.fetchedAt())) {
            return Status.INVALID_PROVENANCE;
        }
        return doc.hasChunks() ? Status.READY : Status.EMPTY_CORPUS;
    }

    private boolean validChunk(RetrievedChunk chunk, Document doc) {
        if (doc == null || chunk.chunkId() <= 0 || !Double.isFinite(chunk.score())
                || !profile.settings().marketplace().equals(chunk.marketplace()) || !doc.version().equals(chunk.version())
                || !doc.title().equals(chunk.document()) || chunk.content().isBlank()
                || !(chunk.sectionPath().equals(doc.title()) || chunk.sectionPath().startsWith(doc.title() + " > "))) {
            return false;
        }
        try {
            return URI.create(chunk.url()).getHost() != null && chunk.url().split("#", 2)[0].equals(doc.url());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Result incomplete(Status status) {
        var profileDescriptor = new AnalyzerDescriptor(profile.settings().version(), profile.hash(), null,
                PolicyChunker.VERSION, embeddings.modelId(),
                PolicyIngestService.pipelineVersion(policyProperties.chunking(), embeddings.modelId()), ALGORITHM);
        return new Result(status, profileDescriptor, List.of());
    }
}
