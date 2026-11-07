package com.thanhdatpb.listingguard.api.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What {@code GET /api/v1/reports/summary} returns, and what the {@code health-report} job and the console page show
 * (ADR-018): counts of the results a client has stored over a period. Nothing in it is text a client typed: only counts,
 * enumerations, rule ids, catalog marks and version strings.
 *
 * <p>The nested records are named so that no two of them, and none of the other endpoints' records, share a simple name
 * (springdoc merges nested records of one simple name).
 */
public record HealthReport(
        @Schema(description = "The days counted, in UTC, both ends included.")
        PeriodView period,
        @Schema(description = "The marketplace the counts are limited to (`ETSY` or `AMAZON`), or null for all.", nullable = true, example = "ETSY")
        String marketplace,
        @Schema(description = "When the report was computed. A result of a pack that deletes results is counted only if it was still "
                + "stored at this instant.", example = "2026-11-09T03:00:00Z")
        Instant generatedAt,
        @Schema(description = "What runs now. Results of the period may have been made under older versions.")
        VersionsView versions,
        @Schema(description = "The numbers of each pack over the whole period.")
        PacksView packs,
        @Schema(description = "The same numbers per ISO week, in order, without gaps (a week with no result is all zeros). "
                + "The first and the last week are clipped to the period and marked `partial` when the ISO week is longer.")
        List<WeekView> byWeek,
        @Schema(description = "Which packs delete their results, after how long, and so from which day their numbers are complete.")
        CoverageView coverageNote) {

    public record PeriodView(
            @Schema(example = "2026-11-02") LocalDate from,
            @Schema(example = "2026-11-08") LocalDate to,
            @Schema(description = "Days covered, both ends counted.", example = "7") int days) {
    }

    public record VersionsView(
            @Schema(description = "The active ruleset of each pack for each marketplace.") List<RulesetView> rulesets,
            @Schema(description = "The trademark catalog this deployment serves.") CatalogView trademarkCatalog) {
    }

    public record RulesetView(
            @Schema(example = "listing-rules") String pack,
            @Schema(example = "ETSY") String marketplace,
            @Schema(example = "etsy-2026-12") String version) {
    }

    public record CatalogView(
            @Schema(description = "`READY`, `DISABLED` (no catalog is configured) or `REFUSED` (the configured one cannot be served).",
                    example = "READY") String state,
            @Schema(description = "The catalog version, only while the state is `READY`.", nullable = true) String version) {
    }

    public record PacksView(ListingRulesView listingRules, TrademarkRiskView trademarkRisk,
                            VariationChecksView variationChecks, PersonalizationView personalizationScreening) {
    }

    @Schema(description = "Results of `POST /api/v1/checks`. A result is a stored one: a cached answer creates no row, so a listing "
            + "checked again with the same analysis is counted once.")
    public record ListingRulesView(
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The pack id; present on the period totals only.", example = "listing-rules") String pack,
            @Schema(description = "Stored results created in the period.", example = "120") long checks,
            @Schema(description = "Results per stored status; every status is present.",
                    example = "{\"COMPLETED\": 120, \"FAILED\": 0, \"PENDING\": 0}") Map<String, Long> statuses,
            @Schema(description = "Findings per severity; the three severities are always present.") List<SeverityCountView> findings,
            @Schema(description = "The publish readiness stored with the results (ADR-014).") ReadinessSummaryView readiness,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The rules with the most findings; present on the period totals only.") RuleRankingView topRules) {
    }

    public record SeverityCountView(
            @Schema(example = "ERROR") String severity,
            @Schema(description = "Findings of this severity.", example = "30") long findings,
            @Schema(description = "Results with at least one finding of this severity.", example = "18") long checksAffected) {
    }

    @Schema(description = "Only results stored with a readiness count here: it is written when `listing-guard.fixes.enabled` is on or by the "
            + "console editor. The others are in `checksWithoutReadiness` and are skipped, never counted as zero.")
    public record ReadinessSummaryView(
            @Schema(description = "Results with a readiness: scored plus incomplete.", example = "40") long checksWithReadiness,
            @Schema(description = "Results with a score.", example = "38") long scoredChecks,
            @Schema(description = "Results whose readiness is `INCOMPLETE` (a rule failed): stored as not ready, with no score.", example = "2") long incompleteChecks,
            @Schema(description = "Results that are ready.", example = "30") long readyChecks,
            @Schema(description = "`readyChecks / checksWithReadiness`, four decimals; null when there is no readiness.", nullable = true, example = "0.75") Double readyRate,
            @Schema(description = "The mean score of the scored results, one decimal; null when there are none.", nullable = true, example = "82.4") Double averageScore,
            @Schema(description = "Results with no readiness stored.", example = "80") long checksWithoutReadiness,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The readiness versions found in the period; present on the period totals only. A mean over two versions is not a trend.") List<String> versions) {
    }

    public record RuleRankingView(
            @Schema(description = "The most items the list holds.", example = "10") int limit,
            @Schema(description = "Every different rule and severity in the period, before the list is cut.", example = "14") long distinctRules,
            @Schema(description = "Most findings first, then most results affected, then the rule id by code point, then the severity.") List<RuleCountView> items) {
    }

    public record RuleCountView(
            @Schema(example = "title_length") String ruleId,
            @Schema(description = "A rule stored at two severities appears twice.", example = "ERROR") String severity,
            @Schema(example = "12") long findings,
            @Schema(description = "Results with at least one such finding.", example = "12") long checksAffected) {
    }

    @Schema(description = "Results of `POST /api/v1/trademark-checks` that are still retained. `UNKNOWN` is its own level and is never "
            + "counted as `LOW`.")
    public record TrademarkRiskView(
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The pack id; present on the period totals only.", example = "trademark-risk") String pack,
            @Schema(example = "90") long checks,
            @Schema(description = "Results per completeness; both values are present.", example = "{\"COMPLETE\": 88, \"INCOMPLETE\": 2}") Map<String, Long> completeness,
            @Schema(description = "Results per stored risk level; the four levels are always present.",
                    example = "{\"HIGH\": 4, \"MEDIUM\": 9, \"LOW\": 70, \"UNKNOWN\": 7}") Map<String, Long> riskLevels,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The catalog marks matched most often; present on the period totals only.") MarkRankingView topMarks) {
    }

    public record MarkRankingView(
            @Schema(example = "10") int limit,
            @Schema(description = "Every different mark in the period, before the list is cut.", example = "5") long distinctMarks,
            @Schema(description = "Most findings first, then most results affected, then the mark by code point.") List<MarkCountView> items) {
    }

    @Schema(description = "A mark of the trademark catalog (the catalog's own display text, never text from a listing).")
    public record MarkCountView(
            @Schema(description = "The catalog's display mark.") String mark,
            @Schema(example = "6") long findings,
            @Schema(description = "Results with at least one finding for this mark.", example = "5") long checksAffected,
            @Schema(description = "The highest risk among the findings for this mark.", example = "HIGH") String highestRisk) {
    }

    @Schema(description = "Results of `POST /api/v1/variation-checks` that are still retained.")
    public record VariationChecksView(
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The pack id; present on the period totals only.", example = "variation-checks") String pack,
            @Schema(example = "30") long checks,
            @Schema(description = "Results per completeness; both values are present.") Map<String, Long> completeness,
            @Schema(description = "Findings per severity; the three severities are always present.") List<SeverityCountView> findings,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The rules with the most findings; present on the period totals only.") RuleRankingView topRules) {
    }

    @Schema(description = "Results of `POST /api/v1/personalization-screenings` that are still retained. `findings` and `topRules` are the "
            + "character rules only; what the trademark scan found is in `trademarkRiskLevels`. No mark is listed: it would say something "
            + "about what a buyer typed.")
    public record PersonalizationView(
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The pack id; present on the period totals only.", example = "personalization-screening") String pack,
            @Schema(example = "25") long checks,
            @Schema(description = "Results per completeness; both values are present.") Map<String, Long> completeness,
            @Schema(description = "Results per state of their trademark scan: `COMPLETE`, `INCOMPLETE` or `NOT_CONFIGURED` (no catalog was served).") Map<String, Long> trademarkStatus,
            @Schema(description = "Results per risk level of their trademark scan; `UNKNOWN` is never counted as `LOW`.") Map<String, Long> trademarkRiskLevels,
            @Schema(description = "Character-rule findings per severity; the three severities are always present.") List<SeverityCountView> findings,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The character rules with the most findings; present on the period totals only.") RuleRankingView topRules) {
    }

    public record WeekView(
            @Schema(description = "The ISO week.", example = "2026-W45") String isoWeek,
            @Schema(description = "The first day of the week that is inside the period.", example = "2026-11-02") LocalDate from,
            @Schema(description = "The last day of the week that is inside the period.", example = "2026-11-08") LocalDate to,
            @Schema(description = "True when the ISO week reaches beyond the period, so it has fewer results: compare rates, not counts.") boolean partial,
            ListingRulesView listingRules, TrademarkRiskView trademarkRisk, VariationChecksView variationChecks,
            PersonalizationView personalizationScreening) {
    }

    public record CoverageView(
            @Schema(description = "True when every pack that deletes results can still show every day of the period.") boolean complete,
            @Schema(description = "A sentence that says it in words.") String text,
            @Schema(description = "The packs that delete their results; `listing-rules` is not among them: its results are not deleted.") List<RetentionView> retention) {
    }

    public record RetentionView(
            @Schema(example = "trademark-risk") String pack,
            @Schema(description = "How long a result is kept, as an ISO-8601 duration, from the configuration.", example = "P30D") String period,
            @Schema(description = "The first day for which every result of the pack is still stored.", example = "2026-10-11") LocalDate coveredFrom) {
    }
}
