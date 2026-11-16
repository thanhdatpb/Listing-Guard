package com.thanhdatpb.listingguard.client.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The answer of {@code POST /api/v1/trademark-checks} (schema {@code TrademarkCheckResult}). A reference warning, not a legal
 * conclusion: show {@code disclaimer}. {@code INCOMPLETE} is not a clean result and {@code UNKNOWN} is not {@code LOW}.
 *
 * @param checkId         the stored result's id
 * @param marketplace     the marketplace code
 * @param rulesetVersion  the trademark ruleset that ran
 * @param catalogVersion  the catalog that was searched
 * @param analysisVersion what decided the findings
 * @param cached          true when a stored complete result was returned
 * @param completeness    whether every rule ran
 * @param riskLevel       the overall risk
 * @param failedRuleIds   the rules that failed (when {@code INCOMPLETE})
 * @param disclaimer      the text to show with the result
 * @param createdAt       when the result was stored (UTC)
 * @param expiresAt       when it stops being retained (UTC)
 * @param productType     the product type the request named, or {@code null}
 * @param catalogStatus   {@code SERVED} when the text was compared with the catalog; {@code NOT_CONFIGURED} when the deployment
 *                        serves none and only the client's watchlist and the rules that need no catalog ran (ADR-031): the result
 *                        is then {@code INCOMPLETE} and the absence of catalog findings says nothing
 * @param skippedRuleIds  the rules that did not run because no catalog is served; empty otherwise
 * @param watchlist       the client's watchlist the check used, or {@code null} when it had no enabled entry
 * @param findings        the matches, with evidence
 */
public record TrademarkCheckResult(
        UUID checkId,
        String marketplace,
        String rulesetVersion,
        String catalogVersion,
        String analysisVersion,
        boolean cached,
        Completeness completeness,
        RiskLevel riskLevel,
        List<String> failedRuleIds,
        String disclaimer,
        Instant createdAt,
        Instant expiresAt,
        String productType,
        TrademarkCatalogStatus catalogStatus,
        List<String> skippedRuleIds,
        WatchlistUsage watchlist,
        List<TrademarkCheckFinding> findings) {

    public TrademarkCheckResult {
        failedRuleIds = Copies.list(failedRuleIds);
        skippedRuleIds = Copies.list(skippedRuleIds);
        findings = Copies.list(findings);
    }
}
