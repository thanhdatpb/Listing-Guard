package com.thanhdatpb.listingguard.api.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.thanhdatpb.listingguard.api.batch.ListingOutcome.Part;
import com.thanhdatpb.listingguard.api.batch.SplitOutput.TrademarkHold;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckStatus;
import com.thanhdatpb.listingguard.api.check.FindingSource;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which rows are held and why, and the two files a run writes, from outcomes made by hand (synthetic data only). */
class SplitOutputTest {

    private static final Instant AT = Instant.parse("2026-11-11T09:00:00Z");

    @TempDir
    Path dir;

    // ---- builders

    private static CheckResult.FindingView finding(String ruleId, Severity severity) {
        return new CheckResult.FindingView(ruleId, FindingSource.RULE, severity, "title", "value", "Thông báo", "Gợi ý");
    }

    private static CheckResult rules(CheckResult.FindingView... findings) {
        return new CheckResult(UUID.randomUUID(), CheckStatus.COMPLETED, "ETSY", "etsy-test", "etsy-test", "hash", false, AT,
                new CheckResult.Summary(0, 0, 0), List.of(findings));
    }

    private static TrademarkCheckResult trademark(RiskLevel risk, Completeness completeness) {
        return new TrademarkCheckResult(UUID.randomUUID(), "ETSY", "trademark-test", "catalog-test", "analysis-test", false,
                completeness, risk, List.of(), "disclaimer", AT, AT.plusSeconds(3600), List.of());
    }

    private static ListingOutcome outcome(Part<CheckResult> rules, Part<TrademarkCheckResult> trademark) {
        return new ListingOutcome(2, "id", null, null, rules, trademark);
    }

    private static ListingOutcome clean() {
        return outcome(Part.done(rules(finding("title_length", Severity.WARNING), finding("tag_count", Severity.INFO))),
                Part.done(trademark(RiskLevel.LOW, Completeness.COMPLETE)));
    }

    private static List<String> reasons(ListingOutcome outcome, TrademarkHold hold) {
        return SplitOutput.holdReasons(outcome, hold);
    }

    // ---- why a row is held

    @Test
    void aRowWithOnlyWarningsInfoAndLowTrademarkRiskPasses() {
        for (TrademarkHold hold : TrademarkHold.values()) {
            assertThat(reasons(clean(), hold)).as(hold.name()).isEmpty();
        }
    }

    @Test
    void anErrorFindingHoldsTheRowWithItsRuleIdAndSeverityOncePerRule() {
        ListingOutcome outcome = outcome(Part.done(rules(finding("price_quantity_positive", Severity.ERROR),
                        finding("tag_length", Severity.WARNING), finding("required_fields", Severity.ERROR),
                        finding("price_quantity_positive", Severity.ERROR))),
                Part.done(trademark(RiskLevel.LOW, Completeness.COMPLETE)));

        assertThat(reasons(outcome, TrademarkHold.HIGH))
                .containsExactly("price_quantity_positive/ERROR", "required_fields/ERROR");
    }

    @Test
    void trademarkHighHoldsByDefaultAndMediumOnlyWhenTheOperatorAsksForIt() {
        ListingOutcome high = outcome(Part.done(rules()), Part.done(trademark(RiskLevel.HIGH, Completeness.COMPLETE)));
        ListingOutcome medium = outcome(Part.done(rules()), Part.done(trademark(RiskLevel.MEDIUM, Completeness.COMPLETE)));

        assertThat(reasons(high, TrademarkHold.HIGH)).containsExactly("trademark/HIGH");
        assertThat(reasons(medium, TrademarkHold.HIGH)).isEmpty();
        assertThat(reasons(medium, TrademarkHold.MEDIUM)).containsExactly("trademark/MEDIUM");
        assertThat(reasons(high, TrademarkHold.MEDIUM)).containsExactly("trademark/HIGH");
        assertThat(reasons(high, TrademarkHold.NONE)).isEmpty();
        assertThat(reasons(medium, TrademarkHold.NONE)).isEmpty();
    }

    @Test
    void anIncompleteOrUnknownTrademarkResultIsHeldNeverReadAsLow() {
        ListingOutcome unknown = outcome(Part.done(rules()), Part.done(trademark(RiskLevel.UNKNOWN, Completeness.INCOMPLETE)));
        ListingOutcome incompleteButLow = outcome(Part.done(rules()),
                Part.done(trademark(RiskLevel.LOW, Completeness.INCOMPLETE)));

        assertThat(reasons(unknown, TrademarkHold.HIGH)).containsExactly("trademark/UNKNOWN");
        assertThat(reasons(unknown, TrademarkHold.MEDIUM)).containsExactly("trademark/UNKNOWN");
        assertThat(reasons(incompleteButLow, TrademarkHold.HIGH)).containsExactly("trademark/UNKNOWN");
        assertThat(reasons(unknown, TrademarkHold.NONE)).isEmpty();
    }

    @Test
    void aTrademarkCatalogThatIsNotServedHoldsNothing() {
        ListingOutcome outcome = outcome(Part.done(rules()), Part.notConfigured("DISABLED: no catalog"));

        assertThat(reasons(outcome, TrademarkHold.HIGH)).isEmpty();
    }

    @Test
    void aRowThatCouldNotBeCheckedIsHeldNeverPassed() {
        assertThat(reasons(ListingOutcome.unreadable(3, "x", "bad cell"), TrademarkHold.NONE))
                .containsExactly("unreadable_row");
        assertThat(reasons(outcome(Part.failed("boom"), Part.done(trademark(RiskLevel.LOW, Completeness.COMPLETE))),
                TrademarkHold.HIGH)).containsExactly("rules/CHECK_FAILED");
        assertThat(reasons(outcome(Part.quotaExceeded("quota-exceeded"), Part.quotaExceeded("quota-exceeded")),
                TrademarkHold.HIGH)).containsExactly("rules/QUOTA_EXCEEDED", "trademark/QUOTA_EXCEEDED");
        assertThat(reasons(outcome(Part.done(rules()), Part.failed("boom")), TrademarkHold.HIGH))
                .containsExactly("trademark/CHECK_FAILED");
        assertThat(reasons(outcome(Part.done(rules()), Part.failed("boom")), TrademarkHold.NONE)).isEmpty();
    }

    @Test
    void theHoldLevelParsesIgnoringCaseAndRefusesAnythingElse() {
        assertThat(TrademarkHold.parse(" medium ")).isEqualTo(TrademarkHold.MEDIUM);
        assertThat(TrademarkHold.parse("none")).isEqualTo(TrademarkHold.NONE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TrademarkHold.parse("low"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("HIGH, MEDIUM or NONE");
    }

    // ---- the two files

    private static List<List<String>> read(Path file, char delimiter) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (MappingIterator<String[]> it = new CsvMapper().enable(CsvParser.Feature.WRAP_AS_ARRAY)
                .readerFor(String[].class).with(CsvSchema.emptySchema().withColumnSeparator(delimiter))
                .readValues(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            while (it.hasNext()) {
                rows.add(List.of(it.next()));
            }
        }
        return rows;
    }

    @Test
    void passedAndHeldKeepTheColumnsOrderAndCellsOfTheInputAndHeldAddsOneColumn() throws IOException {
        List<String> header = List.of("sku", "title", "description");
        BatchMapping.Csv csv = new BatchMapping.Csv(';', StandardCharsets.UTF_8, 0);
        // cells with the delimiter, a quote, a line break and a leading "=" must come back exactly as they were read
        BatchCsvReader.Row passedRow = new BatchCsvReader.Row(2, new String[] {"A-1", "Cốc sứ; xanh", "He said \"hi\"\nsecond line"});
        BatchCsvReader.Row heldRow = new BatchCsvReader.Row(3, new String[] {"A-2", "=SUM(1;2)", ""});
        BatchCsvReader.Row shortRow = new BatchCsvReader.Row(4, new String[] {"A-3"});
        ListingOutcome errorOutcome = outcome(Part.done(rules(finding("required_fields", Severity.ERROR))),
                Part.done(trademark(RiskLevel.HIGH, Completeness.COMPLETE)));

        SplitOutput.Counts counts;
        try (SplitOutput split = new SplitOutput(dir, csv, header, TrademarkHold.HIGH)) {
            split.add(passedRow, clean());
            split.add(heldRow, errorOutcome);
            split.add(shortRow, ListingOutcome.unreadable(4, "A-3", "short"));
            counts = split.counts();
        }

        assertThat(counts).isEqualTo(new SplitOutput.Counts(1, 2));
        assertThat(read(dir.resolve("passed.csv"), ';')).containsExactly(
                List.of("sku", "title", "description"),
                List.of("A-1", "Cốc sứ; xanh", "He said \"hi\"\nsecond line"));
        assertThat(read(dir.resolve("held.csv"), ';')).containsExactly(
                List.of("sku", "title", "description", "hold_reasons"),
                List.of("A-2", "=SUM(1;2)", "", "required_fields/ERROR; trademark/HIGH"),
                List.of("A-3", "", "", "unreadable_row"));
        // no byte order mark and no apostrophe: the cells are the input's, so the files can be fed back to a tool
        assertThat(Files.readAllBytes(dir.resolve("held.csv"))[0]).isNotEqualTo((byte) 0xEF);
        assertThat(Files.readString(dir.resolve("held.csv"), StandardCharsets.UTF_8)).doesNotContain("'=SUM");
    }

    @Test
    void anEmptyRunStillWritesBothFilesWithTheirHeader() throws IOException {
        try (SplitOutput split = new SplitOutput(dir, BatchMapping.Csv.DEFAULT, List.of("a", "b"), TrademarkHold.HIGH)) {
            assertThat(split.counts()).isEqualTo(new SplitOutput.Counts(0, 0));
        }

        assertThat(read(dir.resolve("passed.csv"), ',')).containsExactly(List.of("a", "b"));
        assertThat(read(dir.resolve("held.csv"), ',')).containsExactly(List.of("a", "b", "hold_reasons"));
    }
}
