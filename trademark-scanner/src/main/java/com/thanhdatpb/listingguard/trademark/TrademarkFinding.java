package com.thanhdatpb.listingguard.trademark;

/** One finding: a reference warning with its evidence. It is never a legal conclusion. */
public record TrademarkFinding(String ruleId, RiskLevel risk, String message, String suggestion, Evidence evidence) {

    /** Where the finding comes from: the catalog, a context rule or the client's watchlist (ADR-031). */
    public FindingSource source() {
        return FindingSource.of(ruleId);
    }
}
