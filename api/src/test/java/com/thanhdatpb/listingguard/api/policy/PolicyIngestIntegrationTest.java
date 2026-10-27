package com.thanhdatpb.listingguard.api.policy;

import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.entry;
import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.page;
import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.writeManifest;
import static com.thanhdatpb.listingguard.api.policy.PolicyFixtures.writeRaw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.rag.policy.PolicyParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Ingests two tiny self-written corpora (Etsy and a second marketplace) into a real pgvector database. */
@SpringBootTest
@Import(TestcontainersConfig.class)
class PolicyIngestIntegrationTest {

    private static final String SELLER_PAGE_BODY = """
            <h2 id="selling">Selling basics</h2>
            <p>Every listing must describe the item honestly and show photos of the real item that is for sale.</p>
            <h2 id="fees">Selling fees</h2>
            <p>Sellers pay a listing fee for each item and a transaction fee when the item sells to a buyer.</p>
            """;

    private static final String IP_PAGE_BODY = """
            <h3>Counterfeit items</h3>
            <p>Selling counterfeit branded items is prohibited and listings that infringe a trademark are removed.</p>
            """;

    private static final String OTHER_PAGE_BODY = """
            <h2 id="brands">Brand registry</h2>
            <p>Brand owners enrol in the brand registry to report counterfeit products and protect their trademark.</p>
            """;

    @Autowired
    PolicyIngestService ingest;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path dir;

    @BeforeEach
    void freshCorpus() throws IOException {
        jdbc.update("DELETE FROM policy_document");
        jdbc.update("INSERT INTO marketplace (code, name) VALUES ('OTHER', 'Other market') ON CONFLICT DO NOTHING");
        writeRaw(dir, "ETSY", "seller-policy.html", page("Oct 5, 2026", SELLER_PAGE_BODY));
        writeRaw(dir, "ETSY", "ip-policy.html", page("Jun 15, 2023", IP_PAGE_BODY));
        writeRaw(dir, "OTHER", "brands.html", page("Mar 1, 2025", OTHER_PAGE_BODY));
        writeFullManifest("2026-10-27");
    }

    private void writeFullManifest(String fetchedAt) throws IOException {
        writeManifest(dir,
                entry("seller-policy.html", "ETSY", "Seller Policy", fetchedAt, "2026-10-05"),
                entry("ip-policy.html", "ETSY", "IP Policy", fetchedAt, "2023-06-15"),
                entry("brands.html", "OTHER", "Brand Rules", fetchedAt, "2025-03-01"));
    }

    private List<Long> chunkIds() {
        return jdbc.queryForList("SELECT id FROM policy_chunk ORDER BY id", Long.class);
    }

    private int chunkCount(String marketplace) {
        return jdbc.queryForObject("SELECT count(*) FROM policy_chunk WHERE marketplace_code = ?", Integer.class, marketplace);
    }

    @Test
    void ingestsEveryDocumentAndStoresItsMetadata() {
        PolicyIngestService.Report report = ingest.ingest(dir);

        assertThat(report.count(PolicyIngestService.Outcome.INGESTED)).isEqualTo(3);
        assertThat(chunkCount("ETSY")).isEqualTo(3);
        assertThat(chunkCount("OTHER")).isEqualTo(1);

        Map<String, Object> document = jdbc.queryForMap(
                "SELECT * FROM policy_document WHERE marketplace_code = 'ETSY' AND source_file = 'seller-policy.html'");
        assertThat(document).containsEntry("title", "Seller Policy").containsEntry("version", "2026-10-05")
                .containsEntry("url", "https://example.com/seller-policy/");
        assertThat(document.get("fetched_at").toString()).isEqualTo("2026-10-27");
        assertThat(document.get("source_hash").toString()).matches("[0-9a-f]{64}");
        assertThat(document.get("pipeline_version").toString()).contains("chunker-1").contains("hashing:768");

        Map<String, Object> chunk = jdbc.queryForMap("""
                SELECT section_path, url, content, content_hash, metadata::text AS metadata, vector_dims(embedding) AS dims
                FROM policy_chunk WHERE section_path = 'Seller Policy > Selling fees'
                """);
        assertThat(chunk).containsEntry("url", "https://example.com/seller-policy/#fees").containsEntry("dims", 768);
        assertThat(chunk.get("content").toString()).startsWith("Sellers pay a listing fee");
        assertThat(chunk.get("content_hash").toString()).matches("[0-9a-f]{64}");
        assertThat(chunk.get("metadata").toString()).contains("heading_path").contains("Selling fees");
    }

    @Test
    void runningTwiceCreatesNoDuplicatesAndTouchesNothing() {
        ingest.ingest(dir);
        List<Long> before = chunkIds();

        PolicyIngestService.Report second = ingest.ingest(dir);

        assertThat(second.count(PolicyIngestService.Outcome.UNCHANGED)).isEqualTo(3);
        assertThat(second.count(PolicyIngestService.Outcome.INGESTED)).isZero();
        assertThat(chunkIds()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_document", Integer.class)).isEqualTo(3);
    }

    @Test
    void aChangedDocumentGetsNewChunksAndTheOthersAreLeftAlone() throws IOException {
        ingest.ingest(dir);
        List<Long> otherChunksBefore = jdbc.queryForList(
                "SELECT id FROM policy_chunk WHERE marketplace_code = 'OTHER' ORDER BY id", Long.class);
        List<Long> sellerChunksBefore = jdbc.queryForList(
                "SELECT c.id FROM policy_chunk c JOIN policy_document d ON d.id = c.document_id WHERE d.source_file = 'seller-policy.html' ORDER BY c.id",
                Long.class);

        writeRaw(dir, "ETSY", "seller-policy.html",
                page("Oct 5, 2026", SELLER_PAGE_BODY.replace("a listing fee for each item", "a revised flat fee for each item")));
        PolicyIngestService.Report report = ingest.ingest(dir);

        assertThat(report.documents()).filteredOn(result -> result.file().equals("seller-policy.html"))
                .extracting(PolicyIngestService.DocumentResult::outcome).containsExactly(PolicyIngestService.Outcome.INGESTED);
        assertThat(report.count(PolicyIngestService.Outcome.UNCHANGED)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_chunk WHERE content LIKE '%a listing fee for each item%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_chunk WHERE content LIKE '%a revised flat fee%'", Integer.class)).isEqualTo(1);
        assertThat(chunkCount("ETSY")).isEqualTo(3);
        assertThat(jdbc.queryForList(
                "SELECT c.id FROM policy_chunk c JOIN policy_document d ON d.id = c.document_id WHERE d.source_file = 'seller-policy.html' ORDER BY c.id",
                Long.class)).doesNotContainAnyElementsOf(sellerChunksBefore);
        assertThat(jdbc.queryForList("SELECT id FROM policy_chunk WHERE marketplace_code = 'OTHER' ORDER BY id", Long.class))
                .isEqualTo(otherChunksBefore);
    }

    @Test
    void aDocumentRemovedFromTheManifestIsPrunedButOtherMarketplacesAreLeftAlone() throws IOException {
        ingest.ingest(dir);

        writeManifest(dir, entry("seller-policy.html", "ETSY", "Seller Policy", "2026-10-27", "2026-10-05"));
        PolicyIngestService.Report report = ingest.ingest(dir);

        assertThat(report.documents()).filteredOn(result -> result.outcome() == PolicyIngestService.Outcome.PRUNED)
                .extracting(PolicyIngestService.DocumentResult::file).containsExactly("ip-policy.html");
        assertThat(jdbc.queryForList("SELECT source_file FROM policy_document WHERE marketplace_code = 'ETSY'", String.class))
                .containsExactly("seller-policy.html");
        assertThat(chunkCount("ETSY")).isEqualTo(2);
        assertThat(chunkCount("OTHER")).as("a marketplace absent from the manifest is not touched").isEqualTo(1);
    }

    @Test
    void aNewFetchDateAloneDoesNotReEmbed() throws IOException {
        ingest.ingest(dir);
        List<Long> before = chunkIds();

        writeFullManifest("2026-11-02");
        PolicyIngestService.Report report = ingest.ingest(dir);

        assertThat(report.count(PolicyIngestService.Outcome.UNCHANGED)).isEqualTo(3);
        assertThat(chunkIds()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT fetched_at::text FROM policy_document WHERE source_file = 'seller-policy.html'", String.class))
                .isEqualTo("2026-11-02");
    }

    @Test
    void aManifestVersionThatDisagreesWithThePageFailsBeforeAnythingIsWritten() throws IOException {
        writeManifest(dir,
                entry("ip-policy.html", "ETSY", "IP Policy", "2026-10-27", "2023-06-15"),
                entry("seller-policy.html", "ETSY", "Seller Policy", "2026-10-27", "2026-10-06"));

        assertThatThrownBy(() -> ingest.ingest(dir))
                .isInstanceOf(PolicyParseException.class)
                .hasMessageContaining("seller-policy.html")
                .hasMessageContaining("Last updated");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_document", Integer.class)).isZero();
    }

    @Test
    void aFileMissingFromDiskFailsTheRun() throws IOException {
        Files.delete(dir.resolve("raw").resolve("etsy").resolve("ip-policy.html"));

        assertThatThrownBy(() -> ingest.ingest(dir))
                .isInstanceOf(PolicyParseException.class)
                .hasMessageContaining("ip-policy.html");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_document", Integer.class)).isZero();
    }

    @Test
    void aPageWithNoExtractableContentFailsTheRun() throws IOException {
        writeRaw(dir, "ETSY", "ip-policy.html", "<html><body></body></html>");

        assertThatThrownBy(() -> ingest.ingest(dir))
                .isInstanceOf(PolicyParseException.class)
                .hasMessageContaining("no content");
    }

    @Test
    void aMissingManifestFailsTheRun() throws IOException {
        Files.delete(dir.resolve("manifest.yaml"));

        assertThatThrownBy(() -> ingest.ingest(dir)).isInstanceOf(PolicyParseException.class).hasMessageContaining("manifest");
    }
}
