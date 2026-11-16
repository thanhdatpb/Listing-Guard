package com.thanhdatpb.listingguard.hfconvert;

import static com.thanhdatpb.listingguard.hfconvert.HfFixture.CaseRow;
import static com.thanhdatpb.listingguard.hfconvert.HfFixture.ClassRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
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
