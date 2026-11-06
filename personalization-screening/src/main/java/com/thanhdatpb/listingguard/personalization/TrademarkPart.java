package com.thanhdatpb.listingguard.personalization;

import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.util.List;

/**
 * The trademark half of a screening (ADR-016). {@code riskLevel} is the scanner's own (ADR-009): the highest finding, LOW
 * for a complete scan with none, and for an incomplete scan the highest established MEDIUM or HIGH, otherwise UNKNOWN. When
 * the scanner did not run ({@code NOT_CONFIGURED}) it is UNKNOWN and {@code catalogVersion} and {@code rulesetVersion} are
 * null. UNKNOWN is never turned into LOW.
 */
public record TrademarkPart(TrademarkStatus status, RiskLevel riskLevel, String catalogVersion, String rulesetVersion,
                            List<String> failedRuleIds, List<TrademarkHit> findings) {

    public TrademarkPart {
        failedRuleIds = List.copyOf(failedRuleIds);
        findings = List.copyOf(findings);
    }

    /** The scanner did not run because no catalog is served. */
    public static TrademarkPart notConfigured() {
        return new TrademarkPart(TrademarkStatus.NOT_CONFIGURED, RiskLevel.UNKNOWN, null, null, List.of(), List.of());
    }
}
