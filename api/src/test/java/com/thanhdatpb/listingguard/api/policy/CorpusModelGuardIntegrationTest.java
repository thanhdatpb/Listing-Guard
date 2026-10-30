package com.thanhdatpb.listingguard.api.policy;

import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.entry;
import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.page;
import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.writeManifest;
import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.writeRaw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.ApiTestKeys;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR-012, item 4: after the embedding model changes and before the next ingest, retrieval must refuse instead of
 * ranking vectors of one model against a query of another.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CorpusModelGuardIntegrationTest {

    private static final String OTHER_MODEL = "api:google:gemini-embedding-2:768";

    @Autowired
    PolicyIngestService ingest;

    @Autowired
    PolicyRetriever retriever;

    @Autowired
    EmbeddingProvider embeddings;

    @Autowired
    CorpusModelGuard guard;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @TempDir
    Path dir;

    @BeforeEach
    void ingestCorpus() throws IOException {
        jdbc.update("DELETE FROM policy_document");
        writeRaw(dir, "ETSY", "seller-policy.html", page("Oct 5, 2026", """
                <h2 id="selling">Selling basics</h2>
                <p>Every listing must describe the item honestly and show photos of the real item that is for sale.</p>
                """));
        writeRaw(dir, "ETSY", "ip-policy.html", page("Jun 15, 2023", """
                <h3>Counterfeit items</h3>
                <p>Selling counterfeit branded items that infringe a trademark is prohibited and the listings are removed.</p>
                """));
        writeManifest(dir,
                entry("seller-policy.html", "ETSY", "Seller Policy", "2026-10-27", "2026-10-05"),
                entry("ip-policy.html", "ETSY", "IP Policy", "2026-10-27", "2023-06-15"));
        ingest.ingest(dir);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM policy_document");
    }

    /** What the database holds after ingest ran with another model: same chunker settings, other model id. */
    private void embeddedByAnotherModel(String sourceFile) {
        jdbc.update("UPDATE policy_document SET pipeline_version = replace(pipeline_version, ?, ?) WHERE source_file = ?",
                embeddings.modelId(), OTHER_MODEL, sourceFile);
    }

    @Test
    void searchesNormallyWhileEveryDocumentUsesTheCurrentModel() {
        assertThat(retriever.search("ETSY", "counterfeit trademark", 3)).isNotEmpty();
    }

    @Test
    void anEmptyCorpusIsNotAMismatch() {
        jdbc.update("DELETE FROM policy_document");

        assertThat(retriever.search("ETSY", "counterfeit trademark", 3)).isEmpty();
    }

    @Test
    void refusesToSearchWhenOneDocumentWasEmbeddedByAnotherModel() {
        embeddedByAnotherModel("ip-policy.html");

        assertThatThrownBy(() -> retriever.search("ETSY", "counterfeit trademark", 3))
                .isInstanceOf(CorpusModelMismatchException.class)
                .hasMessageContaining(embeddings.modelId())
                .hasMessageContaining(OTHER_MODEL)
                .hasMessageContaining("ETSY/ip-policy.html")
                .hasMessageContaining("ingest");
    }

    @Test
    void theSearchEndpointAnswersWithAStableProblemCodeAndNoResults() throws Exception {
        embeddedByAnotherModel("seller-policy.html");
        embeddedByAnotherModel("ip-policy.html");

        mvc.perform(get("/policies/search").header(ApiTestKeys.HEADER, ApiTestKeys.KEY_1).param("marketplace", "ETSY").param("q", "counterfeit"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("corpus-model-mismatch"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("2 document(s)")))
                .andExpect(jsonPath("$[0]").doesNotExist());
    }

    @Test
    void theEvaluationJobFailsBeforeItMeasuresAnything() {
        embeddedByAnotherModel("ip-policy.html");
        RetrievalEvalRunner runner = new RetrievalEvalRunner(retriever, embeddings, jdbc, dir.resolve("no-such-eval-dir").toString(), guard);

        assertThatThrownBy(runner::run).isInstanceOf(CorpusModelMismatchException.class);
    }

    @Test
    void ingestingAgainWithTheCurrentModelRepairsTheCorpus() {
        embeddedByAnotherModel("ip-policy.html");
        assertThatThrownBy(() -> retriever.search("ETSY", "counterfeit", 3)).isInstanceOf(CorpusModelMismatchException.class);

        PolicyIngestService.Report report = ingest.ingest(dir);

        assertThat(report.count(PolicyIngestService.Outcome.INGESTED)).isEqualTo(1);
        assertThat(retriever.search("ETSY", "counterfeit", 3)).isNotEmpty();
    }
}
