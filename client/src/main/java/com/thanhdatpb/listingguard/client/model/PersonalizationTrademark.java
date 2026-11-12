package com.thanhdatpb.listingguard.client.model;

import java.util.List;

/**
 * The trademark scan inside a personalization screening (schema {@code TrademarkView}). {@code NOT_CONFIGURED} means no scan
 * ran and the risk is {@code UNKNOWN}, never {@code LOW}.
 *
 * @param status         whether the scan ran and completed
 * @param riskLevel      the overall risk
 * @param rulesetVersion the trademark ruleset, or {@code null}
 * @param catalogVersion the catalog, or {@code null}
 * @param failedRuleIds  the rules that failed
 * @param findings       the matches
 */
public record PersonalizationTrademark(
        TrademarkScanStatus status,
        RiskLevel riskLevel,
        String rulesetVersion,
        String catalogVersion,
        List<String> failedRuleIds,
        List<PersonalizationTrademarkFinding> findings) {

    public PersonalizationTrademark {
        failedRuleIds = Copies.list(failedRuleIds);
        findings = Copies.list(findings);
    }
}
