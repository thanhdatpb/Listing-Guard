package com.thanhdatpb.listingguard.client.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The answer of {@code POST /api/v1/variation-checks} (schema {@code VariationCheckResult}). A reference warning built on
 * secondary sources and heuristics: show {@code disclaimer}.
 *
 * @param checkId         the stored result's id
 * @param marketplace     the marketplace code
 * @param rulesetVersion  the ruleset that ran
 * @param analysisVersion what decided the findings
 * @param cached          true when a stored complete result was returned
 * @param completeness    whether every rule ran
 * @param failedRuleIds   the rules that failed
 * @param disclaimer      the text to show with the result
 * @param createdAt       when the result was stored (UTC)
 * @param expiresAt       when it stops being retained (UTC)
 * @param summary         the number of findings per severity
 * @param findings        the findings
 */
public record VariationCheckResult(
        UUID checkId,
        String marketplace,
        String rulesetVersion,
        String analysisVersion,
        boolean cached,
        Completeness completeness,
        List<String> failedRuleIds,
        String disclaimer,
        Instant createdAt,
        Instant expiresAt,
        Summary summary,
        List<Finding> findings) {

    public VariationCheckResult {
        failedRuleIds = Copies.list(failedRuleIds);
        findings = Copies.list(findings);
    }
}
