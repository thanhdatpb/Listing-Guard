package com.thanhdatpb.listingguard.hfconvert;

import static com.thanhdatpb.listingguard.hfconvert.HfFixture.CaseRow;
import static com.thanhdatpb.listingguard.hfconvert.HfFixture.ClassRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The conversion over small synthetic Parquet files (ADR-030). Every mark and serial number is invented. */
class HfCatalogConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TrademarkRuleset RULESET = TrademarkRulesetLoader.loadClasspath(
            "rulesets/trademark-risk-2026-11b.yaml");
    private static final HfConvertFilters FILTERS = HfConvertFilters.loadDefault();
    /** A code the shipped filters do not keep. */
    private static final String DEAD = "602";

    @TempDir
    Path work;

    private Path dataset;
    private Path temp;

    private static String registered() {
        return firstCode(MarkStatus.REGISTERED);
    }

    private static String pending() {
        return firstCode(MarkStatus.PENDING);
    }

    private static String firstCode(MarkStatus status) {
        return FILTERS.statusCodes().entrySet().stream().filter(e -> e.getValue() == status).findFirst().orElseThrow()
                .getKey();
    }

    /** A class the filters keep and one they do not. */
    private static int kept() {
        return FILTERS.niceClasses().first();
    }

    private static int other() {
        for (int c = 1; c <= 45; c++) {
            if (!FILTERS.niceClasses().contains(c)) {
                return c;
            }
        }
        throw new IllegalStateException();
    }

    private static String code(int niceClass) {
        return String.format("%03d", niceClass);
    }

    record Run(HfConversionReport report, List<JsonNode> lines, String text) {
        Map<String, JsonNode> bySerial() {
            Map<String, JsonNode> map = new LinkedHashMap<>();
            lines.forEach(line -> map.put(line.get("source_id").asText(), line));
            return map;
        }
    }

    private void write(List<CaseRow> rows, List<ClassRow> classes) throws IOException, SQLException {
        dataset = Files.createDirectories(work.resolve("raw/hf"));
        temp = Files.createDirectories(work.resolve("tmp"));
        HfFixture.caseFile(dataset, "data_0", rows);
        HfFixture.intlClass(dataset, "data_0", classes);
        HfFixture.lineage(dataset, rows.stream().map(CaseRow::serial).distinct().count(), classes.size());
        HfFixture.lookup(dataset, FILTERS, Map.of(DEAD, "Dead/Abandoned"));
    }

    private void folders() throws IOException {
        dataset = Files.createDirectories(work.resolve("raw/hf"));
        temp = Files.createDirectories(work.resolve("tmp"));
    }

    private Run run(HfConvertFilters filters, boolean strict) throws IOException {
        HfCatalogConverter converter = new HfCatalogConverter(filters, new TextNormalizer(RULESET.normalization()),
                RULESET.version(), new HfCatalogConverter.Settings(false, strict, "512MB", 1, temp));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HfConversionReport report = converter.convert(new HfDataset.Local(dataset, "hf"), out);
        String text = out.toString(StandardCharsets.UTF_8);
        List<JsonNode> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isEmpty()) {
                lines.add(JSON.readTree(line));
            }
        }
        assertThat(report.recordsWritten() + report.recordErrors() + report.deadRemoved() + report.filteredTotal())
                .as("every serial number is counted once").isEqualTo(report.distinctSerials());
        assertThat(report.recordsWritten()).isEqualTo(lines.size());
        return new Run(report, lines, text);
    }

    private Run run() throws IOException {
        return run(FILTERS, false);
    }

    @Test
    void keepsLiveStatusesWithTheirCatalogStatusAndDropsTheRest() throws Exception {
        write(List.of(
                        CaseRow.live("90000001", "Zorvexa", registered()),
                        CaseRow.live("90000002", "Quillmont", pending()),
                        CaseRow.live("90000003", "Brannokin", DEAD),
                        CaseRow.live("90000004", "Tessivar", null)),
                List.of(ClassRow.of("90000001", 1, code(kept())), ClassRow.of("90000002", 1, code(kept())),
                        ClassRow.of("90000003", 1, code(kept())), ClassRow.of("90000004", 1, code(kept()))));

        Run run = run();

        assertThat(run.bySerial()).containsOnlyKeys("90000001", "90000002");
        assertThat(run.bySerial().get("90000001").get("status").asText()).isEqualTo("REGISTERED");
        assertThat(run.bySerial().get("90000002").get("status").asText()).isEqualTo("PENDING");
        assertThat(run.report().deadRemoved()).isEqualTo(2);
        assertThat(run.report().deadByStatus()).containsExactlyInAnyOrderEntriesOf(Map.of(DEAD, 1L, "missing", 1L));
        assertThat(run.report().completeness()).isEqualTo(Completeness.COMPLETE);
    }

    @Test
    void keepsAMarkWithOneIncludedClassAndWritesAllItsValidClasses() throws Exception {
        write(List.of(
                        CaseRow.live("90000011", "Velmoraq", registered()),
                        CaseRow.live("90000012", "Draxwell", registered()),
                        CaseRow.live("90000013", "Ombritch", registered())),
                List.of(ClassRow.of("90000011", 1, code(other())), ClassRow.of("90000011", 2, code(kept())),
                        ClassRow.of("90000011", 3, "00A"), ClassRow.of("90000011", 4, "200"),
                        ClassRow.of("90000011", 5, code(kept())),
                        ClassRow.of("90000012", 1, code(other()))));

        Run run = run();

        assertThat(run.bySerial()).containsOnlyKeys("90000011");
        List<Integer> classes = new ArrayList<>();
        run.bySerial().get("90000011").get("nice_classes").forEach(n -> classes.add(n.asInt()));
        assertThat(classes).containsExactly(Math.min(kept(), other()), Math.max(kept(), other()));
        assertThat(run.report().ignoredClassCodes()).isEqualTo(2);
        assertThat(run.report().filtered()).containsEntry(HfCatalogConverter.CLASS_NOT_INCLUDED, 1L)
                .containsEntry(HfCatalogConverter.NO_VALID_NICE_CLASS, 1L);
    }

    @Test
    void dropsMarksWithoutWords() throws Exception {
        write(List.of(
                        CaseRow.live("90000021", "Plorivane", registered()).withDraw("2"),
                        CaseRow.live("90000022", "Soundmarq", registered()).withDraw("6"),
                        CaseRow.live("90000023", "Oldivane", registered()).withDraw("0"),
                        CaseRow.live("90000024", "Stylivane", registered()).withDraw("5"),
                        CaseRow.live("90000025", "Designivane", registered()).withDraw("3"),
                        CaseRow.live("90000026", null, registered()),
                        CaseRow.live("90000027", "Nodrawane", registered()).withDraw(null)),
                List.of(ClassRow.of("90000021", 1, code(kept())), ClassRow.of("90000022", 1, code(kept())),
                        ClassRow.of("90000023", 1, code(kept())), ClassRow.of("90000024", 1, code(kept())),
                        ClassRow.of("90000025", 1, code(kept())), ClassRow.of("90000026", 1, code(kept())),
                        ClassRow.of("90000027", 1, code(kept()))));

        Run run = run();

        assertThat(run.bySerial()).containsOnlyKeys("90000024", "90000025");
        assertThat(run.report().filtered())
                .containsEntry(HfCatalogConverter.LOGO_ONLY, 1L)
                .containsEntry(HfCatalogConverter.NO_DRAWING, 1L)
                .containsEntry(HfCatalogConverter.UNKNOWN_DRAWING_CODE, 1L)
                .containsEntry(HfCatalogConverter.BLANK_MARK, 1L)
                .containsEntry(HfCatalogConverter.MISSING_DRAWING_CODE, 1L);
    }

    @Test
    void dropsMarksShorterThanTheMinimumAfterNormalization() throws Exception {
        String shortest = "Q".repeat(FILTERS.minMarkChars());
        String tooShort = "Q".repeat(FILTERS.minMarkChars() - 1);
        List<CaseRow> rows = new ArrayList<>(List.of(
                CaseRow.live("90000031", shortest, registered()),
                CaseRow.live("90000032", "!!!", registered())));
        List<ClassRow> classes = new ArrayList<>(List.of(ClassRow.of("90000031", 1, code(kept())),
                ClassRow.of("90000032", 1, code(kept()))));
        if (!tooShort.isEmpty()) {
            rows.add(CaseRow.live("90000033", tooShort + " -", registered()));
            classes.add(ClassRow.of("90000033", 1, code(kept())));
        }
        write(rows, classes);

        Run run = run();

        assertThat(run.bySerial()).containsOnlyKeys("90000031");
        assertThat(run.report().filtered()).containsEntry(HfCatalogConverter.MARK_EMPTY_AFTER_NORMALIZATION, 1L);
        if (!tooShort.isEmpty()) {
            assertThat(run.report().filtered()).containsEntry(HfCatalogConverter.MARK_TOO_SHORT, 1L);
        }
    }

    @Test
    void minYearKeepsTheRegistrationOrElseTheFilingYear() throws Exception {
        write(List.of(
                        CaseRow.live("90000041", "Elderquin", registered())
                                .withDates(LocalDate.of(2005, 1, 1), LocalDate.of(2010, 6, 1)),
                        CaseRow.live("90000042", "Newquin", pending()).withDates(LocalDate.of(2020, 1, 1), null),
                        CaseRow.live("90000043", "Datelessquin", pending()).withDates(null, null),
                        CaseRow.live("90000044", "Borderquin", registered())
                                .withDates(LocalDate.of(2001, 1, 1), LocalDate.of(2015, 1, 1))),
                List.of(ClassRow.of("90000041", 1, code(kept())), ClassRow.of("90000042", 1, code(kept())),
                        ClassRow.of("90000043", 1, code(kept())), ClassRow.of("90000044", 1, code(kept()))));

        Run run = run(FILTERS.withMinYear(2015), false);

        assertThat(run.bySerial()).containsOnlyKeys("90000042", "90000044");
        assertThat(run.report().minYear()).isEqualTo(2015);
        assertThat(run.report().filtered()).containsEntry(HfCatalogConverter.BEFORE_MIN_YEAR, 1L)
                .containsEntry(HfCatalogConverter.NO_DATE, 1L);
    }

    @Test
    void theLatestRowOfASerialNumberWinsAcrossFilesOfTwoBuilds() throws Exception {
        folders();
        LocalDate older = LocalDate.of(2026, 9, 30);
        HfFixture.caseFile(dataset, "data_0", List.of(
                CaseRow.live("90000051", "Freshmark", registered()),
                CaseRow.live("90000052", "Revivend", registered())));
        // a file left by the previous build: an older state of the first, the same state of the second
        HfFixture.caseFile(dataset, "data_1", List.of(
                CaseRow.live("90000051", "Stalemark", DEAD).withLineage(older, "apc260930.zip"),
                CaseRow.live("90000052", "Revivend", registered())));
        HfFixture.intlClass(dataset, "data_0", List.of(ClassRow.of("90000051", 1, code(kept())),
                ClassRow.of("90000052", 1, code(kept())),
                new ClassRow("90000051", 1, code(other()), older, "apc260930.zip")));
        HfFixture.lineage(dataset, 2, 3);
        HfFixture.lookup(dataset, FILTERS, Map.of());

        Run run = run();

        assertThat(run.bySerial()).containsOnlyKeys("90000051", "90000052");
        JsonNode fresh = run.bySerial().get("90000051");
        assertThat(fresh.get("mark").asText()).isEqualTo("Freshmark");
        assertThat(fresh.get("nice_classes")).hasSize(1);
        assertThat(fresh.get("nice_classes").get(0).asInt()).isEqualTo(kept());
        assertThat(run.report().caseFileRows()).isEqualTo(4);
        assertThat(run.report().duplicateCaseFileRows()).isEqualTo(2);
        assertThat(run.report().completeness()).isEqualTo(Completeness.COMPLETE);
    }

    @Test
    void twoDifferentRowsWithTheSameLatestLineageAreARecordError() throws Exception {
        folders();
        HfFixture.caseFile(dataset, "data_0", List.of(CaseRow.live("90000061", "Twinmarka", registered())));
        HfFixture.caseFile(dataset, "data_1", List.of(CaseRow.live("90000061", "Twinmarkb", registered())));
        HfFixture.intlClass(dataset, "data_0", List.of(ClassRow.of("90000061", 1, code(kept()))));
        HfFixture.lineage(dataset, 1, 1);
        HfFixture.lookup(dataset, FILTERS, Map.of());

        Run run = run();

        assertThat(run.lines()).isEmpty();
        assertThat(run.report().errors()).containsEntry(HfCatalogConverter.CONFLICTING_DUPLICATE, 1L);
        assertThat(run.report().completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThatThrownBy(() -> run(FILTERS, true)).isInstanceOf(HfConvertException.class)
                .hasMessageContaining(HfCatalogConverter.CONFLICTING_DUPLICATE);
    }

    @Test
    void classesOfAnotherLineageAreNotUsed() throws Exception {
        write(List.of(CaseRow.live("90000071", "Driftclass", registered())),
                List.of(new ClassRow("90000071", 1, code(kept()), LocalDate.of(2025, 12, 31), "apc-annual.zip")));

        Run run = run();

        assertThat(run.lines()).isEmpty();
        assertThat(run.report().filtered()).containsEntry(HfCatalogConverter.CLASS_LINEAGE_MISMATCH, 1L);
    }

    @Test
    void invalidSerialNumbersAreRecordErrors() throws Exception {
        write(List.of(CaseRow.live("9000008", "Shortserial", registered()),
                        CaseRow.live("90000082", "Goodserial", registered())),
                List.of(ClassRow.of("9000008", 1, code(kept())), ClassRow.of("90000082", 1, code(kept()))));

        Run run = run();

        assertThat(run.bySerial()).containsOnlyKeys("90000082");
        assertThat(run.report().errors()).containsEntry(HfCatalogConverter.INVALID_SERIAL, 1L);
        assertThat(run.report().completeness()).isEqualTo(Completeness.INCOMPLETE);
    }

    @Test
    void theOutputIsSortedJsonl1WithAStableHash() throws Exception {
        write(List.of(CaseRow.live("90000093", "Gammora", registered()),
                        CaseRow.live("90000091", "Alphora", pending()),
                        CaseRow.live("90000092", "Betora \"Q\"", registered())),
                List.of(ClassRow.of("90000091", 1, code(kept())), ClassRow.of("90000092", 1, code(kept())),
                        ClassRow.of("90000093", 1, code(kept()))));

        Run first = run();
        Run second = run();

        assertThat(first.text()).isEqualTo(second.text());
        assertThat(first.report().outputSha256()).isEqualTo(second.report().outputSha256());
        assertThat(first.bySerial().keySet()).containsExactly("90000091", "90000092", "90000093");
        assertThat(first.text()).doesNotContain("\r").endsWith("\n");
        for (JsonNode line : first.lines()) {
            List<String> keys = new ArrayList<>();
            line.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactly("mark", "status", "nice_classes", "source_id");
        }
        assertThat(first.report().outputBytes()).isEqualTo(first.text().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void theReportCarriesTheBuildAndTheManifestKeys() throws Exception {
        write(List.of(CaseRow.live("90000101", "Reportiva", registered())),
                List.of(ClassRow.of("90000101", 1, code(kept()))));

        HfConversionReport report = run().report();

        assertThat(report.dataset().buildId()).isEqualTo("build-20261102-000000");
        assertThat(report.source().sourceDate()).isEqualTo(HfFixture.BUILD.toString());
        assertThat(report.source().url()).isEqualTo("https://huggingface.co/datasets/" + FILTERS.dataset());
        assertThat(report.source().license()).isEqualTo(HfCatalogConverter.LICENSE).hasSizeLessThanOrEqualTo(200);
        assertThat(report.source().sourceName()).contains(FILTERS.dataset()).contains("build-20261102-000000");
        assertThat(report.files()).allSatisfy(f -> assertThat(f.sha256()).hasSize(64));
        assertThat(report.lookup().checked()).isTrue();
        assertThat(report.lookup().liveCodesNotInFilters()).isEmpty();
        assertThat(report.toJson()).doesNotContain("Reportiva").doesNotContain("90000101");
    }

    @Test
    void aMissingLineageOrADeadCodeInTheFiltersMakesTheRunIncomplete() throws Exception {
        write(List.of(CaseRow.live("90000111", "Lonelyva", registered())),
                List.of(ClassRow.of("90000111", 1, code(kept()))));
        Files.delete(dataset.resolve(HfDataset.LINEAGE));
        HfFixture.lookup(dataset, FILTERS, Map.of(registered(), "Dead/Cancelled", "999", "Live/Pending"));

        HfConversionReport report = run().report();

        assertThat(report.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(report.dataset()).isNull();
        assertThat(report.source().sourceDate()).isNull();
        assertThat(report.lookup().filterCodesNotLive()).containsExactly(registered());
        assertThat(report.lookup().liveCodesNotInFilters()).containsExactly("999");
        assertThat(report.problems()).hasSize(2);
        assertThatThrownBy(() -> run(FILTERS, true)).isInstanceOf(HfConvertException.class);
    }
}
