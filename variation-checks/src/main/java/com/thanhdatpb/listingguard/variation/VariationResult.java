package com.thanhdatpb.listingguard.variation;

import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;

/**
 * The result of checking one variation family (ADR-015). {@code inputHash} is the {@link CanonicalInput} hash. When
 * {@code completeness} is {@code INCOMPLETE} a rule failed: its id is in {@code failedRuleIds} and the findings of the
 * other rules are kept. There is no risk level; an incomplete result is simply never presented as clean.
 */
public record VariationResult(String inputHash, String rulesetVersion, Completeness completeness,
                              List<VariationFinding> findings, List<String> failedRuleIds, String disclaimer) {

    public VariationResult {
        findings = List.copyOf(findings);
        failedRuleIds = List.copyOf(failedRuleIds);
    }

    /** Number of findings per severity. */
    public record Summary(int errors, int warnings, int infos) {
    }

    public Summary summary() {
        return new Summary(count(Severity.ERROR), count(Severity.WARNING), count(Severity.INFO));
    }

    private int count(Severity severity) {
        return (int) findings.stream().filter(finding -> finding.severity() == severity).count();
    }
}
