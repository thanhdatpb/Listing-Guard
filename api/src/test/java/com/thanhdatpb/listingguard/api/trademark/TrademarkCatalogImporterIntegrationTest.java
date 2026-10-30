package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogFormatException;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogHeader;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /** The reference: the same import with the merge rule applied in memory. */
    private PreparedCatalog prepared(String label) {
        TrademarkFixtures.syntheticDir(dir, label);
        return TrademarkFixtures.prepare(dir, label, rulesets.ruleset());
    }

    private TrademarkCatalogImporter.Result importSynthetic(String label) {
        return TrademarkFixtures.importCatalog(importer, dir, label, rulesets.ruleset());
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
        return jdbc.queryForList("SELECT normalized, display, nice_classes::text AS nice_classes, status, "
                        + "common_word, source_ids::text AS source_ids FROM trademark_entry WHERE catalog_version_id = ? "
                        + "ORDER BY normalized COLLATE \"C\"", versionId).stream()
                .map(TrademarkCatalogImporterIntegrationTest::text).toList();
    }

    private List<Map<String, Object>> sourceRows(long versionId) {
        return jdbc.queryForList("SELECT source_key, source_name, url, license, source_date::text AS source_date, "
                        + "sha256 FROM trademark_catalog_source WHERE catalog_version_id = ? "
                        + "ORDER BY source_key NULLS FIRST", versionId).stream()
                .map(row -> {
                    Map<String, Object> copy = new java.util.TreeMap<>(row);
                    copy.put("sha256", row.get("sha256").toString().trim());
                    return copy;
                }).toList();
    }

    /** The entries of the in-memory reference in the shape of {@link #entryRows}. */
    private static List<Map<String, Object>> referenceRows(PreparedCatalog catalog) {
        return catalog.entries().stream().map(entry -> {
            CatalogMark mark = entry.mark();
            Map<String, Object> row = new java.util.TreeMap<>();
            row.put("normalized", mark.normalized());
            row.put("display", mark.display());
            row.put("nice_classes", "{" + String.join(",", mark.niceClasses().stream().map(String::valueOf).toList()) + "}");
            row.put("status", mark.status().name());
            row.put("common_word", String.valueOf(mark.commonWord()));
            row.put("source_ids", "{" + String.join(",", mark.sourceIds()) + "}");
            return row;
        }).toList();
    }

    private void writeTwoSources(String label, String firstData, String secondData, String classes, String statuses)
            throws IOException {
        Files.createDirectories(dir.resolve("raw"));
        Files.writeString(dir.resolve("raw").resolve("first.jsonl"), firstData);
        Files.writeString(dir.resolve("raw").resolve("second.jsonl"), secondData);
        Files.writeString(dir.resolve("manifest.yaml"), """
                catalogs:
                  - version: %s
                    format_version: jsonl-1
                    sources:
                      - source_key: uspto
                        file: first.jsonl
                        source_name: Synthetic public data
                        url: https://example.invalid/public
                        license: test only
                        source_date: 2026-10-30
                        sha256: %s
                      - source_key: internal
                        file: second.jsonl
                        source_name: Synthetic internal list
                        url: internal list
                        license: internal use
                        source_date: 2026-09-01
                        sha256: %s
                    filters:
                      include_classes: %s
                      include_status: %s
                """.formatted(label, CatalogImport.sha256(firstData.getBytes(StandardCharsets.UTF_8)),
                CatalogImport.sha256(secondData.getBytes(StandardCharsets.UTF_8)), classes, statuses));
    }

    @Test
    void storesTheSourceTheHashTheFiltersTheIdentityAndTheCounts() {
        String label = uniqueLabel();
        PreparedCatalog catalog = prepared(label);

        TrademarkCatalogImporter.Result result = importSynthetic(label);

        assertThat(result.outcome()).isEqualTo(TrademarkCatalogImporter.Outcome.IMPORTED);
        assertThat(result.catalog().version()).isEqualTo(catalog.version());
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
        assertThat(row.get("normalization_hash").toString().trim())
                .isEqualTo(rulesets.ruleset().normalization().canonicalHash()).matches("[0-9a-f]{64}");
        assertThat(row.keySet()).doesNotContain("ruleset_version", "ruleset_content_hash");
        assertThat(row.get("normalization_version")).isEqualTo("normalization-1");
        assertThat(row.get("records_read")).isEqualTo(11);
        assertThat(row.get("records_filtered")).isEqualTo(2);
        assertThat(row.get("records_merged")).isEqualTo(2);
        assertThat(row.get("records_loaded")).isEqualTo(7);
        assertThat(row.get("imported_at")).isNotNull();
        assertThat(result.counts()).isEqualTo(new TrademarkCatalogImporter.Counts(11, 2, 2, 7));
    }

    @Test
    void storesTheMergedEntries() {
        String label = uniqueLabel();
        prepared(label);
        long id = importSynthetic(label).catalogVersionId();

        List<Map<String, Object>> entries = entryRows(id);

        assertThat(entries).extracting(e -> e.get("normalized")).containsExactly("blue harbor tools", "brask",
                "harbor light", "quillmark", "zork", "zorvex", "zorvix");
        Map<String, Object> zorvex = entries.get(5);
        assertThat(zorvex.get("display")).isEqualTo("ZORVEX");
        assertThat(zorvex.get("status")).isEqualTo(MarkStatus.REGISTERED.name());
        assertThat(zorvex.get("common_word")).isEqualTo("true");
        // every merged record keeps its id: S-001 (cancelled records are filtered out before the merge) and S-002
        assertThat(zorvex.get("source_ids")).isEqualTo("{S-001,S-002}");
        assertThat(entries.get(1).get("source_ids")).isEqualTo("{S-006,S-007}");
        assertThat(zorvex.get("nice_classes")).isEqualTo("{25,35}");
        assertThat(entries.get(1).get("display")).isEqualTo("Brask");
    }

    @Test
    void theSqlMergeGivesTheEntriesOfTheInMemoryReference() {
        String label = uniqueLabel();
        PreparedCatalog reference = prepared(label);

        long id = importSynthetic(label).catalogVersionId();

        assertThat(entryRows(id)).isEqualTo(referenceRows(reference));
    }

    @Test
    void theSqlMergeAndTheReferenceAgreeOnTrickyRecordsAcrossSources() throws IOException {
        String label = uniqueLabel();
        String first = String.join("\n",
                "{\"mark\":\"Zorvex\",\"status\":\"PENDING\",\"nice_classes\":[35, 25],\"source_id\":\"b-9\"}",
                "{\"mark\":\"ZORVEX!\",\"status\":\"EXPIRED\",\"nice_classes\":[25],\"source_id\":\"b-9\"}",
                "{\"mark\":\"zorvex\",\"status\":\"REGISTERED\",\"nice_classes\":[35],\"source_id\":\"a-1\"}",
                "{\"mark\":\"Brask\",\"status\":\"EXPIRED\",\"nice_classes\":[25],\"source_id\":\"z\"}",
                "{\"mark\":\"Quill\",\"status\":\"CANCELLED\",\"nice_classes\":[25],\"source_id\":\"q\"}") + "\n";
        String second = String.join("\n",
                "{\"mark\":\"Zorvex\",\"status\":\"PENDING\",\"nice_classes\":[25],\"source_id\":\"b-9\",\"common_word\":true}",
                "{\"mark\":\"BRASK\",\"status\":\"PENDING\",\"nice_classes\":[35],\"source_id\":\"A\"}",
                "{\"mark\":\"Brask\",\"status\":\"PENDING\",\"nice_classes\":[9, 25],\"source_id\":\"B\"}") + "\n";
        writeTwoSources(label, first, second, "[25, 35]", "[REGISTERED, PENDING, EXPIRED]");
        PreparedCatalog reference = TrademarkFixtures.prepare(dir, label, rulesets.ruleset());

        TrademarkCatalogImporter.Result result = TrademarkFixtures.importCatalog(importer, dir, label,
                rulesets.ruleset());

        List<Map<String, Object>> rows = entryRows(result.catalogVersionId());
        assertThat(rows).isEqualTo(referenceRows(reference));
        assertThat(rows).extracting(row -> row.get("normalized")).containsExactly("brask", "zorvex");
        Map<String, Object> brask = rows.get(0);
        assertThat(brask.get("display")).isEqualTo("BRASK");
        assertThat(brask.get("status")).as("PENDING is declared before EXPIRED").isEqualTo("PENDING");
        assertThat(brask.get("nice_classes")).isEqualTo("{9,25,35}");
        assertThat(brask.get("source_ids")).isEqualTo("{internal:A,internal:B,uspto:z}");
        Map<String, Object> zorvex = rows.get(1);
        assertThat(zorvex.get("display")).as("smallest in code point order").isEqualTo("ZORVEX!");
        assertThat(zorvex.get("status")).isEqualTo("REGISTERED");
        assertThat(zorvex.get("common_word")).isEqualTo("true");
        assertThat(zorvex.get("nice_classes")).isEqualTo("{25,35}");
        assertThat(zorvex.get("source_ids")).as("ascending, once each").isEqualTo("{internal:b-9,uspto:a-1,uspto:b-9}");
    }

    @Test
    void theBatchSizeChangesNothingInTheResult() {
        String label = uniqueLabel();
        List<Map<String, Object>> expected = referenceRows(prepared(label));

        for (int batchSize : new int[]{1, 2, 3, 7, 5000}) {
            TrademarkCatalogImporter small = new TrademarkCatalogImporter(jdbc, transactions,
                    new TrademarkImportProperties(batchSize));
            // a new label per run: each is another version, with the same entries
            String other = label + "-b" + batchSize;
            TrademarkFixtures.syntheticDir(dir, other);
            TrademarkCatalogImporter.Result result = TrademarkFixtures.importCatalog(small, dir, other,
                    rulesets.ruleset());

            assertThat(entryRows(result.catalogVersionId())).as("batch size " + batchSize).isEqualTo(expected);
            assertThat(result.counts()).isEqualTo(new TrademarkCatalogImporter.Counts(11, 2, 2, 7));
        }
    }

    // --- one source row per file ---

    @Test
    void theOneFileFormWritesExactlyOneSourceRow() {
        String label = uniqueLabel();
        PreparedCatalog catalog = prepared(label);

        long id = importSynthetic(label).catalogVersionId();

        assertThat(sourceRows(id)).hasSize(1);
        Map<String, Object> source = sourceRows(id).get(0);
        assertThat(source.get("source_key")).isNull();
        assertThat(source.get("source_name")).isEqualTo("Synthetic marks");
        assertThat(source.get("url")).isEqualTo("https://example.invalid/synthetic");
        assertThat(source.get("license")).isEqualTo("test only");
        assertThat(source.get("source_date")).isEqualTo("2026-10-30");
        assertThat(source.get("sha256")).isEqualTo(catalog.fileSha256());
    }

    @Test
    void severalSourcesWriteOneRowEachAndASummaryInTheVersionRow() throws IOException {
        String label = uniqueLabel();
        String first = "{\"mark\":\"Zorvex\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"1001\"}\n";
        String second = "{\"mark\":\"ZORVEX\",\"status\":\"REGISTERED\",\"nice_classes\":[35],\"source_id\":\"7\","
                + "\"common_word\":true}\n";
        writeTwoSources(label, first, second, "[25, 35]", "[REGISTERED]");

        TrademarkCatalogImporter.Result result = TrademarkFixtures.importCatalog(importer, dir, label,
                rulesets.ruleset());

        long id = result.catalogVersionId();
        List<Map<String, Object>> sources = sourceRows(id);
        assertThat(sources).extracting(s -> s.get("source_key")).containsExactly("internal", "uspto");
        assertThat(sources.get(0).get("source_name")).isEqualTo("Synthetic internal list");
        assertThat(sources.get(0).get("license")).isEqualTo("internal use");
        assertThat(sources.get(0).get("source_date")).isEqualTo("2026-09-01");
        assertThat(sources.get(0).get("sha256")).isEqualTo(CatalogImport.sha256(second.getBytes(StandardCharsets.UTF_8)));
        assertThat(sources.get(1).get("sha256")).isEqualTo(CatalogImport.sha256(first.getBytes(StandardCharsets.UTF_8)));
        // the summary columns of the version row (V15) keep working
        Map<String, Object> version = versionRow(result.catalog().version());
        assertThat(version.get("source_name")).isEqualTo("internal: Synthetic internal list | uspto: Synthetic public data");
        assertThat(version.get("source_date").toString()).isEqualTo("2026-09-01");
        assertThat(version.get("file_sha256").toString().trim()).isEqualTo(result.catalog().fileSha256());
        // the merged entry has the ids of both files, prefixed, and the common word of the internal list
        Map<String, Object> entry = entryRows(id).get(0);
        assertThat(entry.get("source_ids")).isEqualTo("{internal:7,uspto:1001}");
        assertThat(entry.get("common_word")).isEqualTo("true");
        assertThat(entry.get("nice_classes")).isEqualTo("{25,35}");
    }

    @Test
    void sourceRowsAreAppendOnlyAndGoWithTheirVersion() {
        String label = uniqueLabel();
        prepared(label);
        long id = importSynthetic(label).catalogVersionId();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE trademark_catalog_source SET license = 'other' WHERE catalog_version_id = ?", id))
                .hasMessageContaining("append-only");
        jdbc.update("DELETE FROM trademark_entry WHERE catalog_version_id = ?", id);
        jdbc.update("DELETE FROM trademark_catalog_version WHERE id = ?", id);

        assertThat(sourceRows(id)).isEmpty();
    }

    // --- idempotence, versions, failure ---

    @Test
    void importingTheSameThingAgainChangesNothing() {
        String label = uniqueLabel();
        PreparedCatalog catalog = prepared(label);
        TrademarkCatalogImporter.Result first = importSynthetic(label);
        Map<String, Object> before = versionRow(catalog.version());
        List<Map<String, Object>> entriesBefore = entryRows(first.catalogVersionId());

        TrademarkCatalogImporter.Result again = importSynthetic(label);

        assertThat(again.outcome()).isEqualTo(TrademarkCatalogImporter.Outcome.ALREADY_IMPORTED);
        assertThat(again.catalogVersionId()).isEqualTo(first.catalogVersionId());
        assertThat(again.counts()).isEqualTo(first.counts());
        assertThat(versionRow(catalog.version())).isEqualTo(before);
        assertThat(entryRows(first.catalogVersionId())).isEqualTo(entriesBefore);
        assertThat(sourceRows(first.catalogVersionId())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_version WHERE label = ?", Integer.class,
                catalog.label())).isEqualTo(1);
    }

    @Test
    void aChangedFileOrFilterIsANewVersionAndTheOldOneIsUntouched() {
        String label = uniqueLabel();
        PreparedCatalog original = prepared(label);
        long originalId = importSynthetic(label).catalogVersionId();
        Map<String, Object> versionBefore = versionRow(original.version());
        List<Map<String, Object>> entriesBefore = entryRows(originalId);

        TrademarkFixtures.catalogsDir(dir, label, TrademarkFixtures.resource(TrademarkFixtures.SYNTHETIC)
                + "{\"mark\":\"Newmark\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"S-100\"}\n",
                "[25, 35]", "[REGISTERED, PENDING]");
        TrademarkCatalogImporter.Result fileResult = importSynthetic(label);
        TrademarkFixtures.catalogsDir(dir, label, TrademarkFixtures.resource(TrademarkFixtures.SYNTHETIC), "[25]",
                "[REGISTERED, PENDING]");
        TrademarkCatalogImporter.Result filterResult = importSynthetic(label);

        assertThat(fileResult.outcome()).isEqualTo(TrademarkCatalogImporter.Outcome.IMPORTED);
        assertThat(filterResult.outcome()).isEqualTo(TrademarkCatalogImporter.Outcome.IMPORTED);
        assertThat(List.of(original.version(), fileResult.catalog().version(), filterResult.catalog().version()))
                .doesNotHaveDuplicates();
        assertThat(entryRows(fileResult.catalogVersionId())).hasSize(8);
        assertThat(entryRows(filterResult.catalogVersionId())).hasSizeLessThan(7);
        assertThat(versionRow(original.version())).isEqualTo(versionBefore);
        assertThat(entryRows(originalId)).isEqualTo(entriesBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_version WHERE label = ?", Integer.class,
                label)).isEqualTo(3);
    }

    @Test
    void aBadLineAfterTheFirstBatchesWritesNothing() {
        String label = uniqueLabel();
        String data = TrademarkFixtures.resource(TrademarkFixtures.SYNTHETIC)
                + "{\"mark\":\"SecretMark\",\"status\":\"FAMOUS\",\"nice_classes\":[25],\"source_id\":\"x\"}\n";
        TrademarkFixtures.catalogsDir(dir, label, data, "[25, 35]", "[REGISTERED, PENDING]");
        // a batch of 2: several batches are already in the staging table when the bad line (line 12) is read
        TrademarkCatalogImporter small = new TrademarkCatalogImporter(jdbc, transactions,
                new TrademarkImportProperties(2));

        assertThatThrownBy(() -> TrademarkFixtures.importCatalog(small, dir, label, rulesets.ruleset()))
                .isInstanceOf(CatalogFormatException.class).hasMessageContaining("line 12")
                .hasMessageNotContaining("SecretMark");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_version WHERE label = ?", Integer.class,
                label)).isZero();
        // the staging table did not survive the failed transaction: the next import works
        String again = uniqueLabel();
        TrademarkFixtures.syntheticDir(dir, again);
        assertThat(TrademarkFixtures.importCatalog(small, dir, again, rulesets.ruleset()).outcome())
                .isEqualTo(TrademarkCatalogImporter.Outcome.IMPORTED);
    }

    @Test
    void aFailureOfTheDatabaseWhileTheEntriesAreWrittenLeavesNothing() {
        String label = uniqueLabel();
        String data = TrademarkFixtures.resource(TrademarkFixtures.SYNTHETIC)
                + "{\"mark\":\"Failmark Only\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"F-1\"}\n";
        TrademarkFixtures.catalogsDir(dir, label, data, "[25, 35]", "[REGISTERED, PENDING]");
        // a trigger that refuses one entry, after the version row, the source rows and the entries before it went in
        jdbc.execute("CREATE FUNCTION test_refuse_entry() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'test refuses this entry'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_refuse_entry BEFORE INSERT ON trademark_entry FOR EACH ROW "
                + "WHEN (NEW.display = 'Failmark Only') EXECUTE FUNCTION test_refuse_entry()");
        try {
            assertThatThrownBy(() -> importSynthetic(label)).hasMessageContaining("test refuses this entry");
        } finally {
            jdbc.execute("DROP TRIGGER test_refuse_entry ON trademark_entry");
            jdbc.execute("DROP FUNCTION test_refuse_entry()");
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_version WHERE label = ?", Integer.class,
                label)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_source s WHERE NOT EXISTS "
                + "(SELECT 1 FROM trademark_catalog_version v WHERE v.id = s.catalog_version_id)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_entry WHERE display = 'Failmark Only'",
                Integer.class)).isZero();
    }

    @Test
    void filtersThatKeepNothingWriteNothing() {
        String label = uniqueLabel();
        TrademarkFixtures.catalogsDir(dir, label, TrademarkFixtures.resource(TrademarkFixtures.SYNTHETIC), "[45]",
                "[REGISTERED]");

        assertThatThrownBy(() -> importSynthetic(label)).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("no record is left");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_version WHERE label = ?", Integer.class,
                label)).isZero();
    }

    @Test
    void aFileWhoseHashDiffersIsRefusedBeforeAnythingIsWritten() throws IOException {
        String label = uniqueLabel();
        prepared(label);
        Files.writeString(dir.resolve("raw").resolve("synthetic.jsonl"),
                "{\"mark\":\"Tampered\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"x\"}\n");

        assertThatThrownBy(() -> importSynthetic(label)).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("SHA-256");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_version WHERE label = ?", Integer.class,
                label)).isZero();
    }

    @Test
    void aVersionThatExistsWithOtherContentIsNeverChanged() {
        String label = uniqueLabel();
        prepared(label);
        CatalogManifest.Entry entry = CatalogManifest.load(dir).entry(label);
        CatalogHeader header = CatalogImport.header(entry, NormalizationIdentity.of(rulesets.ruleset()));
        // a row with the version of this import but another license: only a hand-made row can be like this
        jdbc.update("""
                INSERT INTO trademark_catalog_version (version, label, source_name, source_reference, source_license,
                    source_date, file_sha256, format_version, filters_json, pack_id, normalization_version,
                    normalization_hash, records_read, records_filtered, records_merged, records_loaded)
                VALUES (?, ?, ?, ?, 'another license', ?, ?, ?, ?, ?, ?, ?, 1, 0, 0, 1)""", header.version(),
                header.label(), header.sourceName(), header.sourceReference(),
                java.sql.Date.valueOf(header.sourceDate()), header.fileSha256(), header.formatVersion(),
                header.filtersJson(), header.normalization().packId(), header.normalization().normalizationVersion(),
                header.normalization().normalizationHash());

        assertThatThrownBy(() -> importSynthetic(label)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never changed");
        assertThat(versionRow(header.version()).get("source_license")).isEqualTo("another license");
    }

    @Test
    void catalogVersionsAreSeparate() {
        String label = uniqueLabel();
        prepared(label);
        PostgresTrademarkCatalog first = adapter(importSynthetic(label));
        TrademarkFixtures.catalogsDir(dir, "other-set", "{\"mark\":\"Solo Mark\",\"status\":\"REGISTERED\","
                + "\"nice_classes\":[25],\"source_id\":\"X-1\"}\n", "[25]", "[REGISTERED]");
        PostgresTrademarkCatalog second = adapter(TrademarkFixtures.importCatalog(importer, dir, "other-set",
                rulesets.ruleset()));

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
        String label = uniqueLabel();
        PreparedCatalog catalog = prepared(label);

        importSynthetic(label);

        assertThat(output.getAll()).contains(catalog.version()).contains("read 11, filtered 2, merged 2, loaded 7")
                .doesNotContain("Zorvex").doesNotContain("ZORVEX").doesNotContain("Quillmark")
                .doesNotContain("Blue Harbor");
    }
}
