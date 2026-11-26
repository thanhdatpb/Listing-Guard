package com.thanhdatpb.listingguard.hfconvert;

import static com.thanhdatpb.listingguard.hfconvert.HfFixture.CaseRow;
import static com.thanhdatpb.listingguard.hfconvert.HfFixture.ClassRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals.MarkKind;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals.Reason;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals.Tier;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The job end to end: paths, the output files, the import reading them, and no network while the flag is off. */
class HfConvertJobTest {

    private static final TrademarkRuleset RULESET = TrademarkRulesetLoader.loadClasspath(
            "rulesets/trademark-risk-2026-11b.yaml");
    private static final TextNormalizer NORMALIZER = new TextNormalizer(RULESET.normalization());
    private static final HfConvertFilters FILTERS = HfConvertFilters.loadDefault();

    @TempDir
    Path work;

    private Path raw;
    private Path dataset;

    @BeforeEach
    void dataset() throws Exception {
        raw = Files.createDirectories(work.resolve("catalogs/raw"));
        dataset = Files.createDirectories(raw.resolve("hf"));
        String registered = code(MarkStatus.REGISTERED);
        String pending = code(MarkStatus.PENDING);
        String last = String.format("%03d", FILTERS.niceClasses().last());
        String first = String.format("%03d", FILTERS.niceClasses().first());
        HfFixture.caseFile(dataset, "data_0", List.of(
                CaseRow.live("90000201", "Zorvexa", registered),
                CaseRow.live("90000202", "Quillmont Tees", pending),
                CaseRow.live("90000203", "Logoonly", registered).withDraw("2")));
        HfFixture.intlClass(dataset, "data_0", List.of(
                ClassRow.of("90000201", 1, last), ClassRow.of("90000201", 2, first),
                ClassRow.of("90000202", 1, last), ClassRow.of("90000203", 1, last)));
        HfFixture.lineage(dataset, 3, 4);
        HfFixture.lookup(dataset, FILTERS, Map.of());
    }

    private static String code(MarkStatus status) {
        return FILTERS.statusCodes().entrySet().stream().filter(e -> e.getValue() == status).findFirst().orElseThrow()
                .getKey();
    }

    private HfConvertJob job(boolean network) {
        return new HfConvertJob(FILTERS, NORMALIZER, RULESET.version(),
                new HfConvertJob.Options(network, false, "512MB", 1));
    }

    @Test
    void countsOnlyLiveSerialsAndSelectedClassesBeforeMergingNormalizedMarks() throws Exception {
        String live = code(MarkStatus.REGISTERED);
        String first = "%03d".formatted(FILTERS.niceClasses().first());
        String last = "%03d".formatted(FILTERS.niceClasses().last());
        HfFixture.caseFile(dataset, "data_0", List.of(
                CaseRow.live("90000301", "MUG", live),
                CaseRow.live("90000302", "mug", live),
                CaseRow.live("90000303", "Mug", "602"),
                CaseRow.live("90000304", "Quillmont Tees", live),
                CaseRow.live("90000305", "Zorvexa", live).withDraw("3"),
                CaseRow.live("90000306", "Mug", live)));
        HfFixture.intlClass(dataset, "data_0", List.of(
                ClassRow.of("90000301", 1, first), ClassRow.of("90000301", 2, "001"),
                ClassRow.of("90000302", 1, last), ClassRow.of("90000303", 1, first),
                ClassRow.of("90000304", 1, first), ClassRow.of("90000305", 1, first),
                ClassRow.of("90000306", 1, "001")));
        HfFixture.lineage(dataset, 6, 7);
        HfFixture.lookup(dataset, FILTERS, Map.of());
        Files.writeString(raw.resolve("words.txt"), "Mug\nmug\n");

        var result = job(false).run(raw, "hf", "with-signals.jsonl", false, "words.txt", DistinctivenessThresholds.LEGACY, null);
        var report = result.report();
        assertThat(report.deadRemoved()).isEqualTo(1);
        assertThat(report.recordsWritten()).isEqualTo(4);
        assertThat(report.distinctiveness().tierDistribution()).isEqualTo(Map.of("WEAK", 1L, "STRONG", 1L, "ORDINARY", 1L));
        assertThat(report.distinctiveness().topWeak()).containsExactly(new HfConversionReport.MarkCount("mug", 2));
        assertThat(report.distinctiveness().topStrong()).containsExactly(new HfConversionReport.MarkCount("quillmont tees", 1));
        var document = CatalogManifest.parse("catalogs:\n" + HfConvertMain.manifestEntry(result, FILTERS) + "\n");
        var entries = CatalogImport.prepare(document.catalogs().getFirst(), raw, NORMALIZER,
                NormalizationIdentity.of(RULESET)).entries();
        assertThat(entries).extracting(e -> e.mark().distinctiveness().tier()).containsExactly(Tier.WEAK, Tier.STRONG, Tier.ORDINARY);
        var mug = entries.getFirst().mark().distinctiveness();
        assertThat(mug.dictionaryWord()).isTrue();
        assertThat(mug.wordCount()).isEqualTo(1);
        assertThat(mug.sameMarkCount()).isEqualTo(2L);
        assertThat(mug.sameMarkClassCount()).isEqualTo(2);
        assertThat(entries.get(1).mark().distinctiveness().wordCount()).isEqualTo(2);
        assertThat(entries.get(2).mark().distinctiveness().markKind()).isEqualTo(MarkKind.WORDS_WITH_DESIGN);
    }

    @Test
    void weakThresholdsWorkForRepeatedSerialsAndClassesWithoutADictionaryMatch() throws Exception {
        String live = code(MarkStatus.REGISTERED);
        List<Integer> classes = List.copyOf(FILTERS.niceClasses());
        String first = "%03d".formatted(classes.get(0));
        String second = "%03d".formatted(classes.get(1));
        String third = "%03d".formatted(classes.get(2));
        HfFixture.caseFile(dataset, "data_0", List.of(
                CaseRow.live("90000401", "Zorvexa", live),
                CaseRow.live("90000402", "Zorvexa", live),
                CaseRow.live("90000403", "Zorvexa", live),
                CaseRow.live("90000404", "Vexalon", live),
                CaseRow.live("90000405", "Zorvex Mug", live).withDraw("5")));
        HfFixture.intlClass(dataset, "data_0", List.of(
                ClassRow.of("90000401", 1, first), ClassRow.of("90000402", 1, first),
                ClassRow.of("90000403", 1, first), ClassRow.of("90000404", 1, first),
                ClassRow.of("90000404", 2, second), ClassRow.of("90000404", 3, third),
                ClassRow.of("90000405", 1, first)));
        HfFixture.lineage(dataset, 5, 7);
        HfFixture.lookup(dataset, FILTERS, Map.of());
        Files.writeString(raw.resolve("words.txt"), "cup\n");

        var result = job(false).run(raw, "hf", "thresholds.jsonl", false, "words.txt", DistinctivenessThresholds.LEGACY, null);
        assertThat(result.report().distinctiveness().topWeak()).containsExactly(
                new HfConversionReport.MarkCount("zorvexa", 3), new HfConversionReport.MarkCount("vexalon", 1));
        assertThat(result.report().distinctiveness().topStrong()).isEmpty();
        var manifest = CatalogManifest.parse("catalogs:\n" + HfConvertMain.manifestEntry(result, FILTERS));
        var entries = CatalogImport.prepare(manifest.catalogs().getFirst(), raw, NORMALIZER,
                NormalizationIdentity.of(RULESET)).entries();
        assertThat(entries).extracting(e -> e.mark().distinctiveness().dictionaryWord()).containsOnly(false);
        assertThat(entries).extracting(e -> e.mark().distinctiveness().reason())
                .containsExactly(Reason.COMMON_OR_REPEATED, Reason.DRAWING_UNKNOWN, Reason.COMMON_OR_REPEATED);
        assertThat(entries.get(1).mark().distinctiveness().markKind()).isEqualTo(MarkKind.OTHER);
    }

    @Test
    void countsDistinctCurrentOwnersAndRetainsTheLegacyOutput() throws Exception {
        String live = code(MarkStatus.REGISTERED);
        List<Integer> classes = List.copyOf(FILTERS.niceClasses());
        List<CaseRow> cases = new java.util.ArrayList<>();
        List<ClassRow> intl = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String mark = i < 3 ? "Bluespark" : i < 6 ? "Mug" : i < 8 ? "Candle" : i == 8 ? "Quillmont Tees" : "Vexalon";
            String serial = Integer.toString(90000500 + i);
            cases.add(CaseRow.live(serial, mark, i == 9 ? "602" : live));
            intl.add(ClassRow.of(serial, 1, "%03d".formatted(classes.get(i % 3))));
        }
        HfFixture.caseFile(dataset, "data_0", cases);
        HfFixture.intlClass(dataset, "data_0", intl);
        HfFixture.lineage(dataset, cases.size(), intl.size());
        HfFixture.lookup(dataset, FILTERS, Map.of());
        Files.writeString(raw.resolve("words.txt"), "mug\ncandle\n");

        var missing = job(false).run(raw, "hf", "missing-owner.jsonl", false, "words.txt");
        assertThat(missing.report().distinctiveness().owners().state()).isEqualTo("MISSING");
        assertThat(missing.report().distinctiveness().tierDistribution()).isEqualTo(Map.of("WEAK", 0L, "STRONG", 0L, "ORDINARY", 4L));
        assertThat(Files.readString(missing.output())).contains("OWNERS_UNKNOWN").doesNotContain("owner_count_same_mark");
        var old = job(false).run(raw, "hf", "before-owner.jsonl", false, "words.txt", DistinctivenessThresholds.LEGACY, null);

        ownerShard("""
                ('90000500','10','Former Name',@BUILD@,'@DAILY@'),
                ('90000500','40','Bluespark Inc.',@BUILD@,'@DAILY@'),
                ('90000500','70','Correspondent Name',@BUILD@,'@DAILY@'),
                ('90000501','40','BLUEspark L.L.C.',@BUILD@,'@DAILY@'),
                ('90000502','40','Bluespark COMPANY LIMITED',@BUILD@,'@DAILY@'),
                ('90000502','45','Stale Name', DATE '2025-01-01','stale.xml'),
                ('90000503','30','Seller A',@BUILD@,'@DAILY@'),
                ('90000504','40','Seller B',@BUILD@,'@DAILY@'),
                ('90000505','40','Seller C',@BUILD@,'@DAILY@'),
                ('90000506','40','Seller A',@BUILD@,'@DAILY@'),
                ('90000507','40','Seller A LLC',@BUILD@,'@DAILY@'),
                ('90000508','40','Seller C',@BUILD@,'@DAILY@'),
                ('90000509','40','Excluded Owner',@BUILD@,'@DAILY@')
                """.replace("@BUILD@", "DATE '" + HfFixture.BUILD + "'").replace("@DAILY@", HfFixture.DAILY),
                "own_name");
        var result = job(false).run(raw, "hf", "owners.jsonl", false, "words.txt");
        var report = result.report().distinctiveness();
        assertThat(report.tierDistribution()).isEqualTo(Map.of("WEAK", 1L, "STRONG", 2L, "ORDINARY", 1L));
        assertThat(report.owners().marksWithCount()).isEqualTo(4L);
        var entry = CatalogManifest.parse("catalogs:\n" + HfConvertMain.manifestEntry(result, FILTERS)).catalogs().getFirst();
        var byMark = CatalogImport.prepare(entry, raw, NORMALIZER, NormalizationIdentity.of(RULESET)).entries().stream()
                .collect(java.util.stream.Collectors.toMap(item -> item.mark().normalized(), item -> item.mark().distinctiveness()));
        assertThat(byMark.get("bluespark").ownerCountSameMark()).isEqualTo(1);
        assertThat(byMark.get("bluespark").sameMarkCount()).isEqualTo(3);
        assertThat(byMark.get("bluespark").reason()).isEqualTo(Reason.UNIQUE_OWNER_WORD);
        assertThat(byMark.get("mug").ownerCountSameMark()).isEqualTo(3);
        assertThat(byMark.get("candle").reason()).isEqualTo(Reason.DICTIONARY_FEW_OWNERS);
        var legacy = job(false).run(raw, "hf", "legacy.jsonl", false, "words.txt", DistinctivenessThresholds.LEGACY, null);
        assertThat(Files.readAllBytes(legacy.output())).isEqualTo(Files.readAllBytes(old.output()));
        assertThat(Files.readString(legacy.output())).doesNotContain("owner_count_same_mark");
        var noWords = job(false).run(raw, "hf", "no-words-owner.jsonl", false);
        assertThat(noWords.report().distinctiveness().tierDistribution()).containsEntry("ORDINARY", 4L);
    }

    @Test
    void refusesOwnerShardWithUnexpectedSchemaAndNamesActualColumns() throws Exception {
        ownerShard("('90000000','30','Example LLC',DATE '2026-01-01','x.xml')", "different_name");
        assertThatThrownBy(() -> job(false).run(raw, "hf", "bad-owner.jsonl", false))
                .isInstanceOf(HfConvertException.class).hasMessageContaining("actual columns:")
                .hasMessageContaining("different_name=VARCHAR").hasMessageContaining("own_name=VARCHAR");
        assertThat(raw.resolve("bad-owner.jsonl")).doesNotExist();
    }

    private void ownerShard(String rows, String nameColumn) throws Exception {
        var folder = Files.createDirectories(dataset.resolve("data/owner"));
        try (var db = java.sql.DriverManager.getConnection("jdbc:duckdb:"); var sql = db.createStatement()) {
            sql.execute("CREATE TABLE owners (serial_no VARCHAR, own_type_cd VARCHAR, " + nameColumn
                    + " VARCHAR, file_dt_source DATE, file_name_source VARCHAR)");
            sql.execute("INSERT INTO owners VALUES " + rows);
            sql.execute("COPY owners TO '" + HfDataset.duckdbPath(folder.resolve("data_0.parquet")) + "' (FORMAT PARQUET)");
        }
    }

    @Test
    void writesTheCatalogFileAndItsReportThatTrademarkImportReads() throws Exception {
        HfConvertJob.Result result = job(false).run(raw, "hf", "hf-test.jsonl", false);

        assertThat(result.output()).isRegularFile().hasFileName("hf-test.jsonl");
        assertThat(result.reportFile()).isRegularFile().hasFileName("hf-test.jsonl.report.json");
        assertThat(raw.resolve("hf-test.jsonl.part")).doesNotExist();
        assertThat(raw.resolve(HfConvertJob.TEMP_DIR)).doesNotExist();
        assertThat(Files.readString(result.reportFile())).contains(HfConversionReport.FORMAT)
                .contains(result.report().outputSha256());

        // the manifest entry the job prints, as trademark-import reads it (sources form: ids become uspto:<serial>)
        String entry = HfConvertMain.manifestEntry(result, FILTERS);
        CatalogManifest manifest = CatalogManifest.parse("catalogs:\n" + entry + "\n");
        PreparedCatalog catalog = CatalogImport.prepare(manifest.catalogs().get(0), raw, NORMALIZER,
                NormalizationIdentity.of(RULESET));

        assertThat(catalog.recordsRead()).isEqualTo(2);
        assertThat(catalog.entries()).extracting(e -> e.mark().display())
                .containsExactlyInAnyOrder("Zorvexa", "Quillmont Tees");
        CatalogMark zorvexa = catalog.entries().stream().map(PreparedCatalog.Entry::mark)
                .filter(m -> m.display().equals("Zorvexa")).findFirst().orElseThrow();
        assertThat(zorvexa.sourceIds()).containsExactly("uspto:90000201");
        assertThat(zorvexa.status()).isEqualTo(MarkStatus.REGISTERED);
        assertThat(zorvexa.distinctiveness().dictionaryWord()).isNull();
        assertThat(zorvexa.distinctiveness().tier()).isEqualTo(Tier.ORDINARY);
        assertThat(zorvexa.distinctiveness().reason()).isEqualTo(Reason.DICTIONARY_UNAVAILABLE);
        assertThat(zorvexa.niceClasses()).containsExactly(FILTERS.niceClasses().first(), FILTERS.niceClasses().last());
        assertThat(catalog.sourceDate()).isEqualTo(HfFixture.BUILD);
        // the sources form prefixes each joined value with its source key
        assertThat(catalog.sourceLicense()).isEqualTo("uspto: " + HfCatalogConverter.LICENSE);
    }

    @Test
    void refusesAnExistingOutputAndPathsOutsideTheRawDirectory() throws Exception {
        Files.writeString(raw.resolve("taken.jsonl"), "x");

        assertThatThrownBy(() -> job(false).run(raw, "hf", "taken.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--overwrite");
        assertThatThrownBy(() -> job(false).run(raw, "../outside", "a.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inside");
        assertThatThrownBy(() -> job(false).run(raw, "hf", "../a.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain file name");
        assertThat(Files.readString(raw.resolve("taken.jsonl"))).isEqualTo("x");
    }

    @Test
    void aRemoteInputIsRefusedWhileTheNetworkIsOffBeforeDuckDbIsOpened() {
        assertThatThrownBy(() -> job(false).run(raw, "hf://datasets/" + FILTERS.dataset(), "remote.jsonl", false))
                .isInstanceOf(HfConvertException.class).hasMessageContaining("needs the network, which is off");
        assertThat(raw.resolve("remote.jsonl")).doesNotExist();
        assertThat(raw.resolve("remote.jsonl.part")).doesNotExist();

        for (String url : List.of("https://huggingface.co/datasets/x/y", "s3://bucket/data", "hf:datasets/x/y")) {
            assertThatThrownBy(() -> job(false).run(raw, url, "remote.jsonl", false))
                    .isInstanceOf(HfConvertException.class);
        }
    }

    @Test
    void theMainRefusesARemoteInputWithoutTheFlag() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Map<String, String> env = Map.of("CATALOGS_DIR", work.resolve("catalogs").toString());

        int code = HfConvertMain.run(new String[] {"--input=hf://datasets/" + FILTERS.dataset(), "--output=r.jsonl"},
                env::get, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("needs the network, which is off");
    }

    @Test
    void theMainConvertsAndPrintsTheSha256AndAManifestEntry() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Map<String, String> env = Map.of("CATALOGS_DIR", work.resolve("catalogs").toString(),
                "HF_CONVERT_MEMORY_LIMIT", "512MB", "HF_CONVERT_THREADS", "1");

        int code = HfConvertMain.run(new String[] {"--output=main.jsonl"}, env::get,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("sha256: ").contains("source_key: uspto")
                .contains("file: main.jsonl").contains("2 marks written");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("network off");
    }

    @Test
    void duckDbCannotReachAUrlOrAnotherFolderWhileTheNetworkIsOff() throws Exception {
        Path temp = Files.createDirectories(work.resolve("tmp"));
        Path elsewhere = Files.createDirectories(work.resolve("elsewhere"));
        HfFixture.caseFile(elsewhere, "data_0", List.of(CaseRow.live("90000301", "Outsider", code(MarkStatus.PENDING))));
        HfCatalogConverter converter = new HfCatalogConverter(FILTERS, NORMALIZER, RULESET.version(),
                new HfCatalogConverter.Settings(false, false, "256MB", 1, temp));

        try (Connection connection = converter.open(new HfDataset.Local(dataset, "hf"))) {
            assertThat(setting(connection, "enable_external_access")).isEqualTo("false");
            assertThat(setting(connection, "autoinstall_known_extensions")).isEqualTo("false");
            assertThat(setting(connection, "autoload_known_extensions")).isEqualTo("false");
            assertThat(count(connection, "SELECT count(*) FROM read_parquet("
                    + HfCatalogConverter.literal(HfDataset.duckdbPath(dataset.resolve("data/case_file/data_0.parquet")))
                    + ")")).isEqualTo(3);

            assertThatThrownBy(() -> count(connection,
                    "SELECT count(*) FROM read_parquet('https://example.invalid/data.parquet')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> count(connection, "SELECT count(*) FROM read_parquet("
                    + HfCatalogConverter.literal(HfDataset.duckdbPath(elsewhere.resolve("data/case_file/data_0.parquet")))
                    + ")")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, "SET enable_external_access = true"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, "INSTALL httpfs")).isInstanceOf(SQLException.class);
        }
    }

    private static String setting(Connection connection, String name) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_setting('" + name + "')::VARCHAR")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute(sql);
        }
    }
}
