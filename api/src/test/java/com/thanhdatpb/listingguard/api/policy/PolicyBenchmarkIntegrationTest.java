package com.thanhdatpb.listingguard.api.policy;

import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.entry;
import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.page;
import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.writeManifest;
import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.writeRaw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import com.thanhdatpb.listingguard.rag.eval.EvalSet;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class PolicyBenchmarkIntegrationTest {
    private static final Instant AS_OF = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired private PolicyIngestService ingest;
    @Autowired private PolicyRetriever vector;
    @Autowired private EmbeddingProvider embeddings;
    @Autowired private JdbcTemplate jdbc;
    @TempDir private Path dir;

    @Test
    void comparesRankingsOnTheSameCorpusAndWritesReproducibleEvidence() throws IOException {
        EvalSet set = set();
        ingestFixture();

        String report = benchmark(set);
        assertThat(benchmark(set)).isEqualTo(report);
        assertThat(report).contains("## Vector baseline", "## Hybrid", "Recall@5", "MRR@10", "Expected source/version coverage@5",
                "No hits@10", "No relevant hits@10", "https://example.com/images/", "2026-04-20 | true",
                "| ambiguous-wording | vi |").doesNotContain("Other marketplace canary", "Synthetic text only");

        var lexical = new HybridPolicyBenchmark(jdbc);
        var keywordOnly = lexical.search("ETSY", "ảnh mô phỏng cốc", 5, (market, query, k) -> List.of());
        assertThat(keywordOnly).hasSize(1);
        assertThat(keywordOnly.getFirst().sourceFile()).isEqualTo("images.html");
        assertThat(lexical.search("ETSY", "tokennotfound", 5, (market, query, k) -> List.of())).isEmpty();

        String noVector = RetrievalEvalRunner.benchmark(set, (market, query, k) -> List.of(), jdbc, AS_OF, embeddings.modelId());
        assertThat(noVector).contains("| en | 1.000 (4/4) | 1.000 (4/4) | 0.000 (0/3) | n/a (0/0) |");

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/policy-benchmark.md"), report);
    }

    @Test
    void failsClosedForMissingSourcesAndInvalidProvenance() throws IOException {
        EvalSet set = set();
        assertThatThrownBy(() -> benchmark(set)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing labelled");
        ingestFixture();
        jdbc.update("UPDATE policy_document SET source_hash = 'bad' WHERE source_file = 'images.html'");
        assertThatThrownBy(() -> benchmark(set)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid policy provenance");
    }

    @Test
    void failsClosedWhenReturnedVersionDiffersFromTheCatalog() throws IOException {
        ingestFixture();
        PolicyRetriever forged = (market, query, k) -> List.of(new PolicyRetriever.RetrievedChunk(
                1, 0.9, market, "Mock imagery", "images.html", "Mock imagery > Product imagery",
                "https://example.com/images/#imagery", "other-version", ""));
        EvalSet set = set();
        assertThatThrownBy(() -> RetrievalEvalRunner.benchmark(set, forged, jdbc, AS_OF, embeddings.modelId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not match the corpus provenance");
    }

    private static EvalSet set() throws IOException {
        return EvalSet.load(new ClassPathResource("policy-benchmark/queries.yaml").getFile().toPath());
    }

    private String benchmark(EvalSet set) {
        return RetrievalEvalRunner.benchmark(set, vector, jdbc, AS_OF, embeddings.modelId());
    }

    private void ingestFixture() throws IOException {
        jdbc.update("DELETE FROM policy_document");
        writeRaw(dir, "ETSY", "disclosure.html", page("Apr 1, 2026", """
                <h2 id="partner">Partner disclosure</h2>
                <p>Synthetic text only. Disclose production partner in the sample worksheet.
                   Khai báo đối tác sản xuất trong phiếu minh họa.</p>
                <h2 id="language">Ambiguous wording</h2>
                <p>Designed by us made with help is ambiguous. Chúng tôi thiết kế và có hỗ trợ là câu mơ hồ.</p>
                """));
        writeRaw(dir, "ETSY", "images.html", page("Apr 1, 2026", """
                <h2 id="imagery">Product imagery</h2>
                <p>The sample contrasts a mockup photo of a mug with a studio image. Hình minh họa là ảnh mô phỏng cốc.</p>
                """));
        writeRaw(dir, "ETSY", "rights.html", page("Apr 1, 2026", """
                <h2 id="rights">Design rights</h2>
                <p>Permission to use a design is a separate question. Quyền sử dụng thiết kế được ghi riêng.
                   A second sample contrasts an image license with consent to print a design.</p>
                """));
        writeRaw(dir, "AMAZON", "decoy.html", page("Apr 1, 2026", """
                <h2 id="canary">Other marketplace canary</h2>
                <p>Disclose production partner, mockup photo of a mug, permission to use a design, ảnh mô phỏng cốc.</p>
                """));
        writeManifest(dir, entry("disclosure.html", "ETSY", "Sample disclosure", "2026-10-20", "2026-04-01"),
                entry("images.html", "ETSY", "Mock imagery", "2026-04-20", "2026-04-01"),
                entry("rights.html", "ETSY", "Sample rights", "2026-10-20", "2026-04-01"),
                entry("decoy.html", "AMAZON", "Other marketplace canary", "2026-10-20", "2026-04-01"));
        ingest.ingest(dir);
    }
}
