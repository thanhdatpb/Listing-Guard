package com.thanhdatpb.listingguard.rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.thanhdatpb.listingguard.rag.policy.ParsedPolicy;
import com.thanhdatpb.listingguard.rag.policy.PolicyChunk;
import com.thanhdatpb.listingguard.rag.policy.PolicyChunker;
import com.thanhdatpb.listingguard.rag.policy.PolicyManifest;
import com.thanhdatpb.listingguard.rag.policy.PolicyManifestLoader;
import com.thanhdatpb.listingguard.rag.policy.PolicyParser;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Guards the committed evaluation set: it must parse, and its labels must point at sections that exist. */
class EvalLabelsTest {

    private static final Path EVAL_SET = Path.of("..", "eval", "retrieval", "queries.yaml");
    private static final Path POLICIES = Path.of("..", "policies");

    @Test
    void theCommittedEvaluationSetParsesWithBothLanguagesForEveryQuery() {
        EvalSet set = EvalSet.load(EVAL_SET);

        assertThat(set.marketplace()).isEqualTo("ETSY");
        assertThat(set.queries()).hasSizeBetween(15, 20);
        assertThat(set.queries()).allSatisfy(query -> assertThat(query.questions().keySet()).containsExactlyInAnyOrder("en", "vi"));
    }

    @Test
    void everyLabelMatchesAtLeastOneChunkOfTheLocalCorpus() throws IOException {
        Path manifestFile = POLICIES.resolve("manifest.yaml");
        assumeTrue(Files.exists(manifestFile), "no policies/manifest.yaml");
        PolicyManifest manifest = PolicyManifestLoader.load(manifestFile);
        PolicyChunker chunker = new PolicyChunker(new PolicyChunker.Config(1500, 200));

        List<RetrievedChunk> corpus = new ArrayList<>();
        for (PolicyManifest.Entry entry : manifest.documents()) {
            Path file = POLICIES.resolve("raw").resolve(entry.marketplace().toLowerCase(Locale.ROOT)).resolve(entry.file());
            if (!Files.exists(file)) {
                continue;
            }
            ParsedPolicy parsed = PolicyParser.forFile(entry.file()).parse(Files.readAllBytes(file));
            for (PolicyChunk chunk : chunker.chunk(entry.title(), parsed)) {
                corpus.add(new RetrievedChunk(chunk.ordinal(), 0, entry.marketplace(), entry.title(), entry.file(),
                        chunk.sectionPath(), entry.url(), entry.version(), chunk.content()));
            }
        }
        assumeTrue(!corpus.isEmpty(), "no raw policy files saved locally");

        for (EvalSet.Query query : EvalSet.load(EVAL_SET).queries()) {
            for (EvalSet.Expected expected : query.expected()) {
                assertThat(corpus).as("%s expects %s > %s", query.id(), expected.file(), expected.section())
                        .anyMatch(expected::matches);
            }
        }
    }
}
