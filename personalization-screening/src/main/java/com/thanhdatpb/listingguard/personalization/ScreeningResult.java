package com.thanhdatpb.listingguard.personalization;

import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;

/**
 * The result of screening one order's values (ADR-016). {@code inputHash} is the {@link CanonicalInput} hash,
 * {@code printProfile} the profile code used (the default made explicit). When {@code completeness} is
 * {@code INCOMPLETE}, a character rule failed ({@code failedRuleIds}) or the trademark part is not {@code COMPLETE}; the
 * findings that were established are kept. There is no overall verdict: the character findings and the trademark level
 * stand next to each other.
 */
public record ScreeningResult(String inputHash, String rulesetVersion, String printProfile, Completeness completeness,
                              List<TextFinding> findings, List<String> failedRuleIds, TrademarkPart trademark,
                              String disclaimer) {

    public ScreeningResult {
        findings = List.copyOf(findings);
        failedRuleIds = List.copyOf(failedRuleIds);
    }

    /** Number of character findings per severity. */
    public record Summary(int errors, int warnings, int infos) {
    }

    public Summary summary() {
        return new Summary(count(Severity.ERROR), count(Severity.WARNING), count(Severity.INFO));
    }

    private int count(Severity severity) {
        return (int) findings.stream().filter(finding -> finding.severity() == severity).count();
    }
}
