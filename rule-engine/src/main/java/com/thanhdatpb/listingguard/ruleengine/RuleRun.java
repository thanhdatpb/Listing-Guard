package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.core.finding.Finding;
import java.util.List;

/**
 * The result of one rule run (ADR-014): the findings, with every fix already verified, and the ids of the rules that
 * failed and were skipped. A run in which a rule failed is incomplete: its findings are real but a clean result is not
 * proof of a clean listing, so a score must not be computed from it as if it were.
 *
 * @param packId the pack of the ruleset that was run, so a consumer can refuse a run of another pack
 */
public record RuleRun(String packId, List<Finding> findings, List<String> failedRuleIds) {

    public RuleRun {
        findings = List.copyOf(findings);
        failedRuleIds = List.copyOf(failedRuleIds);
    }

    public boolean complete() {
        return failedRuleIds.isEmpty();
    }
}
