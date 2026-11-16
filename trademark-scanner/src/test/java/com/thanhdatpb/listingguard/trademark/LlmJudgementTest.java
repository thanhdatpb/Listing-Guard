package com.thanhdatpb.listingguard.trademark;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.LlmJudgement.Judged;
import com.thanhdatpb.listingguard.trademark.LlmJudgement.Verdict;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmJudgementTest {

    private static TrademarkFinding finding(String ruleId, RiskLevel risk, List<RiskAdjustment> adjustments) {
        Evidence evidence = new Evidence("title", 0, 5, "Dodger", "dodger", "DODGER", MatchType.EXACT, 1.0, "cat-1", ruleId, false,
                List.of(), List.of("sn1"), adjustments, List.of());
        return new TrademarkFinding(ruleId, risk, "message", "suggestion", evidence);
    }

    private static TrademarkResult result(Completeness completeness, TrademarkFinding... findings) {
        RiskLevel level = TrademarkScanner.riskLevel(List.of(findings), completeness);
        return new TrademarkResult("hash", "ruleset", "cat-1", level, completeness, List.of(findings), List.of(), "disclaimer");
    }

    private static final Judged DESCRIPTIVE = new Judged(Verdict.LIKELY_DESCRIPTIVE, "Dodger blue là tên màu.");

    @Test
    void onlyMediumCatalogFindingsAreEligible() {
        assertThat(LlmJudgement.eligible(finding("catalog_exact", RiskLevel.MEDIUM, List.of()))).isTrue();
        assertThat(LlmJudgement.eligible(finding("catalog_exact", RiskLevel.HIGH, List.of()))).isFalse();
        assertThat(LlmJudgement.eligible(finding("catalog_exact", RiskLevel.LOW, List.of()))).isFalse();
        assertThat(LlmJudgement.eligible(finding("watchlist_match", RiskLevel.MEDIUM, List.of()))).isFalse();
        assertThat(LlmJudgement.eligible(finding("geo_sport_combination", RiskLevel.MEDIUM, List.of()))).isFalse();
    }

    @Test
    void descriptiveLowersMediumToLowKeepsTheFindingAndSaysWhy() {
        TrademarkResult before = result(Completeness.COMPLETE, finding("catalog_exact", RiskLevel.MEDIUM, List.of()));
        TrademarkResult after = LlmJudgement.apply(before, Map.of(0, DESCRIPTIVE), false);

        assertThat(after.findings()).hasSize(1);
        TrademarkFinding lowered = after.findings().get(0);
        assertThat(lowered.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(lowered.message()).isEqualTo("message");
        RiskAdjustment adjustment = lowered.evidence().riskAdjustments().get(0);
        assertThat(adjustment.reason()).isEqualTo(AdjustmentReason.LLM_DESCRIPTIVE);
        assertThat(adjustment.from()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(adjustment.to()).isEqualTo(RiskLevel.LOW);
        assertThat(adjustment.note()).startsWith(LlmJudgement.LABEL).contains("Dodger blue là tên màu.");
        assertThat(after.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(after.completeness()).isEqualTo(Completeness.COMPLETE);
    }

    @Test
    void anAdjustmentKeepsTheLevelTheRuleAssigned() {
        RiskAdjustment common = new RiskAdjustment(AdjustmentReason.COMMON_WORD, RiskLevel.HIGH, RiskLevel.MEDIUM, "common");
        TrademarkResult before = result(Completeness.COMPLETE, finding("catalog_exact", RiskLevel.MEDIUM, List.of(common)));
        TrademarkResult after = LlmJudgement.apply(before, Map.of(0, DESCRIPTIVE), false);

        List<RiskAdjustment> adjustments = after.findings().get(0).evidence().riskAdjustments();
        assertThat(adjustments).hasSize(2);
        assertThat(adjustments.get(1).from()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void otherVerdictsAndIneligibleFindingsChangeNothing() {
        TrademarkResult before = result(Completeness.COMPLETE, finding("catalog_exact", RiskLevel.HIGH, List.of()),
                finding("catalog_exact", RiskLevel.MEDIUM, List.of()), finding("watchlist_match", RiskLevel.MEDIUM, List.of()),
                finding("catalog_fuzzy", RiskLevel.LOW, List.of()));
        TrademarkResult after = LlmJudgement.apply(before, Map.of(
                0, DESCRIPTIVE, 1, new Judged(Verdict.LIKELY_MARK_USE, "x"), 2, DESCRIPTIVE, 3, DESCRIPTIVE,
                9, DESCRIPTIVE, 1000, Judged.UNSURE), false);

        assertThat(after).isSameAs(before);
    }

    @Test
    void neverRemovesAFindingNorRaisesALevel() {
        TrademarkResult before = result(Completeness.COMPLETE, finding("catalog_exact", RiskLevel.HIGH, List.of()),
                finding("catalog_exact", RiskLevel.MEDIUM, List.of()));
        TrademarkResult after = LlmJudgement.apply(before, Map.of(1, DESCRIPTIVE), false);

        assertThat(after.findings()).hasSameSizeAs(before.findings());
        for (int i = 0; i < before.findings().size(); i++) {
            assertThat(after.findings().get(i).risk().ordinal()).isLessThanOrEqualTo(before.findings().get(i).risk().ordinal());
        }
        assertThat(after.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void aSkippedStepMakesTheResultIncompleteAndKeepsTheLevel() {
        TrademarkResult before = result(Completeness.COMPLETE, finding("catalog_exact", RiskLevel.MEDIUM, List.of()));
        TrademarkResult after = LlmJudgement.apply(before, Map.of(), true);

        assertThat(after.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(after.skippedRuleIds()).containsExactly(LlmJudgement.SKIPPED_ID);
        assertThat(after.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(after.findings()).isEqualTo(before.findings());
    }
}
