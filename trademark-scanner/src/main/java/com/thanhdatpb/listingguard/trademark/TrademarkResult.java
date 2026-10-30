package com.thanhdatpb.listingguard.trademark;

import java.util.List;

/**
 * The outcome of one scan (ADR-009, ADR-010).
 *
 * <p>{@code riskLevel} is the highest level among the findings, {@code LOW} when a complete scan found nothing.
 * When {@code completeness} is {@code INCOMPLETE} some rules failed ({@code failedRuleIds}): the findings of the
 * rules that succeeded are kept, the level is at least the highest established one, and when no MEDIUM or HIGH
 * finding was established the level is {@code UNKNOWN}, never {@code LOW}.
 *
 * @param inputHash      the canonical input hash ({@link CanonicalInput})
 * @param rulesetVersion version of the ruleset that produced the result
 * @param catalogVersion version of the catalog that produced the result
 * @param disclaimer     the ruleset's statement that the result is a reference warning
 */
public record TrademarkResult(String inputHash, String rulesetVersion, String catalogVersion, RiskLevel riskLevel,
                              Completeness completeness, List<TrademarkFinding> findings,
                              List<String> failedRuleIds, String disclaimer) {

    public TrademarkResult {
        findings = List.copyOf(findings);
        failedRuleIds = List.copyOf(failedRuleIds);
    }
}
