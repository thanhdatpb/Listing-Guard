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

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
 * Retrieval against a real pgvector database. A second marketplace holds many chunks that are closer to the
 * query than anything in the first, to prove the marketplace filter really comes first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PolicyRetrievalIntegrationTest {

    private static final int OTHER_SECTIONS = 60;

    @Autowired
    PolicyIngestService ingest;

    @Autowired
    PolicyRetriever retriever;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @TempDir
    Path dir;

    @BeforeEach
    void ingestCorpus() throws IOException {
        jdbc.update("DELETE FROM policy_document");
        jdbc.update("INSERT INTO marketplace (code, name) VALUES ('OTHER', 'Other market') ON CONFLICT DO NOTHING");

        writeRaw(dir, "ETSY", "seller-policy.html", page("Oct 5, 2026", """
                <h2 id="selling">Selling basics</h2>
                <p>Every listing must describe the item honestly and show photos of the real item that is for sale.</p>
                <h2 id="fees">Selling fees</h2>
                <p>Sellers pay a listing fee for each item and a transaction fee when the item sells to a buyer.</p>
                """));
        writeRaw(dir, "ETSY", "ip-policy.html", page("Jun 15, 2023", """
                <h3>Counterfeit items</h3>
                <p>Selling counterfeit branded items that infringe a trademark is prohibited and the listings are removed.</p>
                """));

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < OTHER_SECTIONS; i++) {
            many.append("<h2 id=\"s").append(i).append("\">Counterfeit trademark rule ").append(i).append("</h2>")
                    .append("<p>Counterfeit branded items that infringe a trademark are prohibited, counterfeit counterfeit counterfeit, rule ")
                    .append(i).append(".</p>");
        }
        writeRaw(dir, "OTHER", "brands.html", page("Mar 1, 2025", many.toString()));

        writeManifest(dir,
                entry("seller-policy.html", "ETSY", "Seller Policy", "2026-10-27", "2026-10-05"),
                entry("ip-policy.html", "ETSY", "IP Policy", "2026-10-27", "2023-06-15"),
                entry("brands.html", "OTHER", "Brand Rules", "2026-10-27", "2025-03-01"));
        ingest.ingest(dir);
    }

    @Test
    void onlyChunksOfTheRequestedMarketplaceAreReturnedEvenWhenAnotherMarketplaceIsCloser() {
        List<RetrievedChunk> results = retriever.search("ETSY", "counterfeit trademark", 10);

        assertThat(results).isNotEmpty().allSatisfy(chunk -> assertThat(chunk.marketplace()).isEqualTo("ETSY"));
        assertThat(results).extracting(RetrievedChunk::document).doesNotContain("Brand Rules");
    }

    @Test
    void fillsAsManyResultsAsTheMarketplaceHasEvenIfThatIsFewerThanTopK() {
        // ETSY has 3 chunks; 60 closer OTHER chunks must not push them out or cause fewer to come back
        assertThat(retriever.search("ETSY", "counterfeit trademark", 10)).hasSize(3);
        assertThat(retriever.search("OTHER", "counterfeit trademark", 10)).hasSize(10);
    }

    @Test
    void ranksTheRelevantSectionFirstAndOrdersByScore() {
        List<RetrievedChunk> results = retriever.search("ETSY", "counterfeit branded items trademark", 3);

        assertThat(results.get(0).sectionPath()).isEqualTo("IP Policy > Counterfeit items");
        assertThat(results).extracting(RetrievedChunk::score).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void resultsCarryTheirProvenance() {
        RetrievedChunk fees = retriever.search("ETSY", "listing fee transaction fee sells to a buyer", 1).get(0);

        assertThat(fees.sectionPath()).isEqualTo("Seller Policy > Selling fees");
        assertThat(fees.document()).isEqualTo("Seller Policy");
        assertThat(fees.sourceFile()).isEqualTo("seller-policy.html");
        assertThat(fees.url()).isEqualTo("https://example.com/seller-policy/#fees");
        assertThat(fees.version()).isEqualTo("2026-10-05");
        assertThat(fees.content()).startsWith("Sellers pay a listing fee");
        assertThat(fees.score()).isBetween(-1.0, 1.0).isGreaterThan(0.3);
        assertThat(fees.chunkId()).isPositive();
    }

    @Test
    void aPageWithoutAnchorsHasThePlainDocumentUrl() {
        RetrievedChunk ip = retriever.search("ETSY", "counterfeit", 1).get(0);

        assertThat(ip.url()).isEqualTo("https://example.com/ip-policy/");
    }

    @Test
    void marketplaceCodeIsCaseInsensitiveAndUnknownMarketplacesFindNothing() {
        assertThat(retriever.search("etsy", "counterfeit", 5)).isNotEmpty();
        assertThat(retriever.search("NOPE", "counterfeit", 5)).isEmpty();
    }

    @Test
    void badArgumentsAreRejected() {
        assertThatThrownBy(() -> retriever.search(" ", "q", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retriever.search("ETSY", " ", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retriever.search("ETSY", "q", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retriever.search("ETSY", "q", PgVectorPolicyRetriever.MAX_TOP_K + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- debug endpoint ----------------------------------------------------------------------------

    @Test
    void searchEndpointReturnsChunksWithScoreUrlAndSectionPath() throws Exception {
        mvc.perform(get("/policies/search").param("marketplace", "ETSY").param("q", "counterfeit branded items").param("k", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sectionPath").value("IP Policy > Counterfeit items"))
                .andExpect(jsonPath("$[0].url").value("https://example.com/ip-policy/"))
                .andExpect(jsonPath("$[0].score").isNumber())
                .andExpect(jsonPath("$[0].content").isNotEmpty())
                .andExpect(jsonPath("$[*].marketplace").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ETSY"))));
    }

    @Test
    void searchEndpointDefaultsKToFive() throws Exception {
        mvc.perform(get("/policies/search").param("marketplace", "OTHER").param("q", "counterfeit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void searchEndpointRejectsBadRequests() throws Exception {
        mvc.perform(get("/policies/search").param("marketplace", "ETSY")).andExpect(status().isBadRequest());
        mvc.perform(get("/policies/search").param("q", "x")).andExpect(status().isBadRequest());
        mvc.perform(get("/policies/search").param("marketplace", "ETSY").param("q", "x").param("k", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/policies/search").param("marketplace", "ETSY").param("q", "x").param("k", "many"))
                .andExpect(status().isBadRequest());
    }
}
