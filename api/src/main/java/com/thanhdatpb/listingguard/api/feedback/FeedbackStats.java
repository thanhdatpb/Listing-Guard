package com.thanhdatpb.listingguard.api.feedback;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.thanhdatpb.listingguard.api.report.HealthReport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What {@code GET /api/v1/feedback/stats} returns and the console statistics page shows (ADR-034): verdicts on findings and the
 * findings they compare with, per rule and per reviewer, over a period. No note and no text of a listing is in it.
 *
 * <p>The nested records have names no other response record uses (springdoc merges nested records of one simple name).
 */
public record FeedbackStats(
        @Schema(description = "The days counted, in UTC, both ends included.")
        HealthReport.PeriodView period,
        @Schema(description = "The marketplace the counts are limited to, or null for all.", nullable = true, example = "ETSY")
        String marketplace,
        @Schema(description = "When the numbers were computed.", example = "2026-11-16T03:00:00Z")
        Instant generatedAt,
        @Schema(description = "Every verdict given in the period.")
        VerdictTotalsView totals,
        @Schema(description = "One entry per rule and source that had a finding or a verdict in the period: most false positives "
                + "first, then most verdicts, then most findings, then pack and rule id.")
        List<RuleFeedbackView> byRule,
        @Schema(description = "One entry per reviewer who gave a verdict in the period, most verdicts first.")
        List<ReviewerFeedbackView> byReviewer) {

    @Schema(description = "Verdicts are counted by when they were last given (a changed verdict counts once, as it is now).")
    public record VerdictTotalsView(
            @Schema(description = "Verdicts given in the period.", example = "40") long verdicts,
            @Schema(description = "Verdicts per verdict; the three verdicts are always present.",
                    example = "{\"CORRECT\": 30, \"FALSE_POSITIVE\": 8, \"NOT_USEFUL\": 2}") Map<String, Long> byVerdict,
            @Schema(description = "`FALSE_POSITIVE / verdicts`, four decimals; null when there is no verdict. What reviewers "
                    + "reported, not a measured precision.", nullable = true, example = "0.2") Double falsePositiveRate) {
    }

    public record RuleFeedbackView(
            @Schema(example = "listing-rules") String pack,
            @Schema(example = "title_repeated_words") String ruleId,
            @Schema(description = "`RULE`, `CATALOG` (a trademark finding that matched a catalog mark) or `WATCHLIST` (reserved).",
                    example = "RULE") String source,
            @Schema(description = "Findings of this rule in the results created in the period (retained packs: only while kept).",
                    example = "25") long findings,
            @Schema(description = "Verdicts given in the period.", example = "10") long verdicts,
            @Schema(example = "6") long correct,
            @Schema(example = "3") long falsePositive,
            @Schema(example = "1") long notUseful,
            @Schema(description = "`falsePositive / verdicts`, four decimals; null when there is no verdict.", nullable = true,
                    example = "0.3") Double falsePositiveRate) {
    }

    public record ReviewerFeedbackView(
            @Schema(description = "`ACCOUNT` (a console account) or `API_KEY`.", example = "ACCOUNT") String kind,
            @Schema(description = "The account id, or the public key id of the API key. Never an e-mail.",
                    example = "0f9e7c1a-2b3c-4d5e-8f90-1a2b3c4d5e6f") String id,
            @JsonIgnore @Schema(hidden = true) String display,
            @Schema(example = "12") long verdicts,
            @Schema(example = "9") long correct,
            @Schema(example = "2") long falsePositive,
            @Schema(example = "1") long notUseful) {

        /** The account's e-mail or the key's label, for the console of the same client only; never in the API's answer. */
        @JsonIgnore
        public String display() {
            return display;
        }
    }
}
