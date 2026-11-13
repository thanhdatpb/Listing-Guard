package com.thanhdatpb.listingguard.api.console.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Detail;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Item;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Kind;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the table says about a row, from what the three checks found (ADR-029). Synthetic findings only. */
class BulkAssessmentTest {

    private static Item item(String level, String message) {
        return new Item(level, BulkText.severityLabel(level), "rule_" + level, "title", message, "do something", null, null, null);
    }

    private static BulkResult.Rules rules(String readiness, Item... items) {
        return new BulkResult.Rules(true, null, readiness, "INCOMPLETE".equals(readiness) ? null : 90, List.of(items));
    }

    private static BulkResult.Trademark tm(Kind kind, String level, Item... items) {
        return new BulkResult.Trademark(kind, level, null, List.of(items));
    }

    private static BulkResult.Trademark tmNotRun(String note) {
        return BulkResult.Trademark.notRun(note);
    }

    private static BulkResult.Variation variation(Kind kind, Item... items) {
        return new BulkResult.Variation(kind, kind == Kind.NOT_RUN ? "gói không gồm" : null, List.of(items));
    }

    private static Detail detail(BulkResult.Rules rules, BulkResult.Trademark trademark, BulkResult.Variation variation) {
        return new Detail(rules, trademark, variation, null);
    }

    private static final BulkResult.Variation NO_FAMILY = new BulkResult.Variation(Kind.NONE, null, List.of());

    @Test
    void aRowWithNothingFoundIsOkAndSaysSo() {
        BulkAssessment.Assessed assessed = BulkAssessment.of(detail(rules("READY"), tm(Kind.RESULT, null), NO_FAMILY));

        assertThat(assessed.status()).isEqualTo(Status.OK);
        assertThat(assessed.findings()).isZero();
        assertThat(assessed.main()).isNull();
        assertThat(assessed.summary()).isEqualTo("Không có phát hiện.");
    }

    @Test
    void anErrorOfTheRulesMakesTheRowAnErrorAndIsItsMainFinding() {
        BulkAssessment.Assessed assessed = BulkAssessment.of(detail(
                rules("NOT_READY", item("warning", "a warning"), item("error", "an error")),
                tm(Kind.RESULT, "HIGH", item("high", "a mark")), NO_FAMILY));

        assertThat(assessed.status()).isEqualTo(Status.ERROR);
        assertThat(assessed.findings()).isEqualTo(3);
        assertThat(assessed.main().message()).isEqualTo("an error");
        assertThat(assessed.summary()).startsWith("3 phát hiện. an error");
    }

    @Test
    void theMainFindingFollowsTheFixedOrder() {
        // rule ERROR, trademark HIGH, rule WARNING, variation WARNING, trademark MEDIUM, information, trademark LOW
        assertThat(BulkAssessment.rank("rules", "error")).isLessThan(BulkAssessment.rank("trademark", "high"));
        assertThat(BulkAssessment.rank("trademark", "high")).isLessThan(BulkAssessment.rank("rules", "warning"));
        assertThat(BulkAssessment.rank("rules", "warning")).isLessThan(BulkAssessment.rank("variation", "warning"));
        assertThat(BulkAssessment.rank("variation", "warning")).isLessThan(BulkAssessment.rank("trademark", "medium"));
        assertThat(BulkAssessment.rank("trademark", "medium")).isLessThan(BulkAssessment.rank("rules", "info"));
        assertThat(BulkAssessment.rank("rules", "info")).isLessThan(BulkAssessment.rank("trademark", "low"));

        BulkAssessment.Assessed assessed = BulkAssessment.of(detail(rules("READY", item("warning", "rule warning")),
                tm(Kind.RESULT, "HIGH", item("high", "mark risk")), variation(Kind.RESULT, item("warning", "family warning"))));
        assertThat(assessed.status()).isEqualTo(Status.WARNING);
        assertThat(assessed.main().message()).isEqualTo("mark risk");
    }

    @Test
    void aWarningOfTheRulesOrOfAFamilyMakesAWarning() {
        assertThat(BulkAssessment.of(detail(rules("READY", item("warning", "w")), tm(Kind.RESULT, null), NO_FAMILY)).status())
                .isEqualTo(Status.WARNING);
        assertThat(BulkAssessment.of(detail(rules("READY"), tm(Kind.RESULT, null), variation(Kind.RESULT, item("warning", "w"))))
                .status()).isEqualTo(Status.WARNING);
    }

    @Test
    void aTrademarkRiskOfMediumOrHighIsAWarningAndALowOneOnlyCounts() {
        assertThat(BulkAssessment.of(detail(rules("READY"), tm(Kind.RESULT, "MEDIUM", item("medium", "m")), NO_FAMILY)).status())
                .isEqualTo(Status.WARNING);
        assertThat(BulkAssessment.of(detail(rules("READY"), tm(Kind.RESULT, "HIGH", item("high", "h")), NO_FAMILY)).status())
                .isEqualTo(Status.WARNING);

        BulkAssessment.Assessed low = BulkAssessment.of(detail(rules("READY"), tm(Kind.RESULT, "LOW", item("low", "l")), NO_FAMILY));
        assertThat(low.status()).isEqualTo(Status.OK);
        assertThat(low.findings()).isEqualTo(1);
        assertThat(low.main().message()).isEqualTo("l");
    }

    @Test
    void anIncompleteResultIsNeverReadAsClean() {
        assertThat(BulkAssessment.of(detail(rules("READY"), tm(Kind.INCOMPLETE, null), NO_FAMILY)).status())
                .as("trademark incomplete").isEqualTo(Status.WARNING);
        assertThat(BulkAssessment.of(detail(rules("INCOMPLETE"), tm(Kind.RESULT, null), NO_FAMILY)).status())
                .as("a rule could not run").isEqualTo(Status.WARNING);
        assertThat(BulkAssessment.of(detail(rules("READY"), tm(Kind.RESULT, null), variation(Kind.INCOMPLETE))).status())
                .as("variation incomplete").isEqualTo(Status.WARNING);
    }

    @Test
    void whatDidNotRunIsSaidInTheSummaryAndNeverTurnsIntoAFailure() {
        BulkAssessment.Assessed noTrademark = BulkAssessment.of(detail(rules("READY"), tmNotRun("hết quota"), NO_FAMILY));
        assertThat(noTrademark.status()).isEqualTo(Status.OK);
        assertThat(noTrademark.summary()).contains("[Chưa kiểm tra nhãn hiệu: hết quota]");

        BulkAssessment.Assessed noVariation = BulkAssessment.of(detail(rules("READY"), tm(Kind.RESULT, null), variation(Kind.NOT_RUN)));
        assertThat(noVariation.summary()).contains("[Chưa kiểm tra biến thể: gói không gồm]");
    }

    @Test
    void aRowWhoseRulesDidNotRunIsNotCheckedAndAnUnreadableRowSaysWhy() {
        BulkAssessment.Assessed quota = BulkAssessment.of(detail(BulkResult.Rules.notRun("hết quota tháng"), tmNotRun("x"), NO_FAMILY));
        assertThat(quota.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(quota.summary()).isEqualTo("Chưa kiểm tra: hết quota tháng");

        BulkAssessment.Assessed unread = BulkAssessment.of(Detail.unread("Dữ liệu sai: giá"));
        assertThat(unread.status()).isEqualTo(Status.UNREADABLE);
        assertThat(unread.summary()).isEqualTo("Không đọc được: Dữ liệu sai: giá");
    }

    @Test
    void theSummaryIsOneLineAndCutAtACodePoint() {
        BulkAssessment.Assessed multiline = BulkAssessment.of(detail(
                rules("NOT_READY", item("error", "first line\nsecond\tline  with   gaps")), tm(Kind.RESULT, null), NO_FAMILY));
        assertThat(multiline.summary()).doesNotContain("\n").doesNotContain("\t").contains("first line second line with gaps");

        String longMessage = "x".repeat(300);
        BulkAssessment.Assessed cut = BulkAssessment.of(detail(rules("NOT_READY", item("error", longMessage)),
                tm(Kind.RESULT, null), NO_FAMILY));
        assertThat(cut.summary().codePointCount(0, cut.summary().length())).isEqualTo(BulkAssessment.SUMMARY_MAX_CHARS);
        assertThat(cut.summary()).endsWith("…");

        String emoji = "😀".repeat(300);
        assertThat(BulkAssessment.summary(emoji)).doesNotContain("�").hasSizeLessThanOrEqualTo(BulkAssessment.SUMMARY_MAX_CHARS * 2);
    }
}
