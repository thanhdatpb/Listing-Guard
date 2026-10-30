package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class TrademarkCatalogImporterIntegrationTest {

    @Autowired
    TrademarkCatalogImporter importer;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @TempDir
    Path dir;

    private static String uniqueLabel() {
        return "synthetic-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private PreparedCatalog prepared(String label) {
        TrademarkFixtures.syntheticDir(dir, label);
        return TrademarkFixtures.prepare(dir, label, rulesets.ruleset());
    }

    /** Every value as text, so rows compare by content (a SQL array is not equal to another copy of itself). */
    private static Map<String, Object> text(Map<String, Object> row) {
        Map<String, Object> result = new java.util.TreeMap<>();
        row.forEach((key, value) -> result.put(key, String.valueOf(value)));
        return result;
    }

    private Map<String, Object> versionRow(String version) {
        return jdbc.queryForMap("SELECT * FROM trademark_catalog_version WHERE version = ?", version);
    }

    private List<Map<String, Object>> entryRows(long versionId) {
        return jdbc.queryForList("SELECT id, catalog_version_id, normalized, display, nice_classes::text AS nice_classes, status, "
                        + "common_word, source_id FROM trademark_entry WHERE catalog_version_id = ? ORDER BY normalized",
                versionId).stream().map(TrademarkCatalogImporterIntegrationTest::text).toList();
    }

    @Test
    void storesTheSourceTheHashTheFiltersTheIdentityAndTheCounts() {
        PreparedCatalog catalog = prepared(uniqueLabel());

        TrademarkCatalogImporter.Result result = importer.importCatalog(catalog);

        assertThat(result.outcome()).isEqualTo(TrademarkCatalogImporter.Outcome.IMPORTED);
        Map<String, Object> row = versionRow(catalog.version());
        assertThat(row.get("label")).isEqualTo(catalog.label());
        assertThat(row.get("source_name")).isEqualTo("Synthetic marks");
        assertThat(row.get("source_reference")).isEqualTo("https://example.invalid/synthetic");
        assertThat(row.get("source_license")).isEqualTo("test only");
        assertThat(row.get("source_date").toString()).isEqualTo("2026-10-30");
        assertThat(row.get("file_sha256").toString().trim()).isEqualTo(catalog.fileSha256()).matches("[0-9a-f]{64}");
        assertThat(row.get("format_version")).isEqualTo("jsonl-1");
        assertThat(row.get("filters_json")).isEqualTo("{\"include_classes\":[25,35],\"include_status\":[\"REGISTERED\",\"PENDING\"]}");
        assertThat(row.get("pack_id")).isEqualTo("trademark-risk");
        assertThat(row.get("ruleset_version")).isEqualTo(rulesets.ruleset().version());
        assertThat(row.get("ruleset_content_hash").toString().trim()).isEqualTo(rulesets.ruleset().contentHash());
        assertThat(row.get("normalization_version")).isEqualTo("normalization-1");
        assertThat(row.get("records_read")).isEqualTo(11);
        assertThat(row.get("records_filtered")).isEqualTo(2);
        assertThat(row.get("records_merged")).isEqualTo(2);
        assertThat(row.get("records_loaded")).isEqualTo(7);
        assertThat(row.get("imported_at")).isNotNull();
    }

    @Test
    void storesTheMergedEntries() {
        PreparedCatalog catalog = prepared(uniqueLabel());
        long id = importer.importCatalog(catalog).catalogVersionId();

        List<Map<String, Object>> entries = entryRows(id);

        assertThat(entries).extracting(e -> e.get("normalized")).containsExactly("blue harbor tools", "brask",
                "harbor light", "quillmark", "zork", "zorvex", "zorvix");
        Map<String, Object> zorvex = entries.get(5);
        assertThat(zorvex.get("display")).isEqualTo("ZORVEX");
        assertThat(zorvex.get("status")).isEqualTo(MarkStatus.REGISTERED.name());
        assertThat(zorvex.get("common_word")).isEqualTo("true");
        assertThat(zorvex.get("source_id")).isEqualTo("S-002");
        assertThat(zorvex.get("nice_classes")).isEqualTo("{25,35}");
        assertThat(entries.get(1).get("display")).isEqualTo("Brask");
    }

    @Test
    void importingTheSameThingAgainChangesNothing() {
        PreparedCatalog catalog = prepared(uniqueLabel());
        TrademarkCatalogImporter.Result first = importer.importCatalog(catalog);
        Map<String, Object> before = versionRow(catalog.version());
        List<Map<String, Object>> entriesBefore = entryRows(first.catalogVersionId());

        TrademarkCatalogImporter.Result again = importer.importCatalog(catalog);

        assertThat(again.outcome()).isEqualTo(TrademarkCatalogImporter.Outcome.ALREADY_IMPORTED);
        assertThat(again.catalogVersionId()).isEqualTo(first.catalogVersionId());
        assertThat(versionRow(catalog.version())).isEqualTo(before);
        assertThat(entryRows(first.catalogVersionId())).isEqualTo(entriesBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_version WHERE label = ?", Integer.class,
                catalog.label())).isEqualTo(1);
    }

    @Test
    void aChangedFileOrFilterIsANewVersionAndTheOldOneIsUntouched() {
        String label = uniqueLabel();
        PreparedCatalog original = prepared(label);
        long originalId = importer.importCatalog(original).catalogVersionId();
        Map<String, Object> versionBefore = versionRow(original.version());
        List<Map<String, Object>> entriesBefore = entryRows(originalId);

        TrademarkFixtures.catalogsDir(dir, label, TrademarkFixtures.resource(TrademarkFixtures.SYNTHETIC)
                + "{\"mark\":\"Newmark\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"S-100\"}\n",
                "[25, 35]", "[REGISTERED, PENDING]");
        PreparedCatalog changedFile = TrademarkFixtures.prepare(dir, label, rulesets.ruleset());
        TrademarkFixtures.catalogsDir(dir, label, TrademarkFixtures.resource(TrademarkFixtures.SYNTHETIC), "[25]",
                "[REGISTERED, PENDING]");
        PreparedCatalog changedFilter = TrademarkFixtures.prepare(dir, label, rulesets.ruleset());

        TrademarkCatalogImporter.Result fileResult = importer.importCatalog(changedFile);
        TrademarkCatalogImporter.Result filterResult = importer.importCatalog(changedFilter);

        assertThat(fileResult.outcome()).isEqualTo(TrademarkCatalogImporter.Outcome.IMPORTED);
        assertThat(filterResult.outcome()).isEqualTo(TrademarkCatalogImporter.Outcome.IMPORTED);
        assertThat(List.of(original.version(), changedFile.version(), changedFilter.version())).doesNotHaveDuplicates();
        assertThat(entryRows(fileResult.catalogVersionId())).hasSize(8);
        assertThat(entryRows(filterResult.catalogVersionId())).hasSizeLessThan(7);
        assertThat(versionRow(original.version())).isEqualTo(versionBefore);
        assertThat(entryRows(originalId)).isEqualTo(entriesBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_version WHERE label = ?", Integer.class,
                label)).isEqualTo(3);
    }

    @Test
    void aFailureInTheMiddleWritesNothing() {
        PreparedCatalog good = prepared(uniqueLabel());
        List<PreparedCatalog.Entry> entries = new java.util.ArrayList<>(good.entries());
        // the database refuses an entry without a class, after the version row and the first entries went in
        entries.add(new PreparedCatalog.Entry(new CatalogMark("zzz last", "ZZZ Last", List.of(), MarkStatus.REGISTERED, false), "S-999"));
        PreparedCatalog broken = new PreparedCatalog(good.version(), good.label(), good.sourceName(),
                good.sourceReference(), good.sourceLicense(), good.sourceDate(), good.fileSha256(), good.formatVersion(),
                good.filtersJson(), good.normalization(), good.recordsRead(), good.recordsFiltered(),
                good.recordsMerged(), entries);

        assertThatThrownBy(() -> importer.importCatalog(broken)).hasMessageContaining("ck_trademark_entry_classes");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_version WHERE version = ?",
                Integer.class, good.version())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_entry WHERE display = 'ZZZ Last'",
                Integer.class)).isZero();
    }

    @Test
    void aVersionThatExistsWithOtherContentIsNeverChanged() {
        PreparedCatalog catalog = prepared(uniqueLabel());
        importer.importCatalog(catalog);
        PreparedCatalog impostor = new PreparedCatalog(catalog.version(), catalog.label(), catalog.sourceName(),
                catalog.sourceReference(), "another license", catalog.sourceDate(), catalog.fileSha256(),
                catalog.formatVersion(), catalog.filtersJson(), catalog.normalization(), catalog.recordsRead(),
                catalog.recordsFiltered(), catalog.recordsMerged(), catalog.entries());

        assertThatThrownBy(() -> importer.importCatalog(impostor)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never changed");
        assertThat(versionRow(catalog.version()).get("source_license")).isEqualTo("test only");
    }

    @Test
    void catalogVersionsAreSeparate() {
        PostgresTrademarkCatalog first = adapter(importer.importCatalog(prepared(uniqueLabel())));
        TrademarkFixtures.catalogsDir(dir, "other-set", "{\"mark\":\"Solo Mark\",\"status\":\"REGISTERED\","
                + "\"nice_classes\":[25],\"source_id\":\"X-1\"}\n", "[25]", "[REGISTERED]");
        PostgresTrademarkCatalog second = adapter(importer.importCatalog(
                TrademarkFixtures.prepare(dir, "other-set", rulesets.ruleset())));

        assertThat(first.findExact(List.of("solo mark", "zorvex")).keySet()).containsExactly("zorvex");
        assertThat(second.findExact(List.of("solo mark", "zorvex")).keySet()).containsExactly("solo mark");
        assertThat(second.fuzzyCandidates(List.of("zorvx"), 0.3)).isEmpty();
        assertThat(first.fuzzyCandidates(List.of("zorvx"), 0.3)).containsOnlyKeys("zorvx");
    }

    private PostgresTrademarkCatalog adapter(TrademarkCatalogImporter.Result result) {
        return new PostgresTrademarkCatalog(jdbc, transactions, result.catalogVersionId(), result.catalog().version());
    }

    @Test
    void theLogHasCountsAndNeverTheMarks(CapturedOutput output) {
        PreparedCatalog catalog = prepared(uniqueLabel());

        importer.importCatalog(catalog);

        assertThat(output.getAll()).contains(catalog.version()).contains("read 11, filtered 2, merged 2, loaded 7")
                .doesNotContain("Zorvex").doesNotContain("ZORVEX").doesNotContain("Quillmark")
                .doesNotContain("Blue Harbor");
    }
}
