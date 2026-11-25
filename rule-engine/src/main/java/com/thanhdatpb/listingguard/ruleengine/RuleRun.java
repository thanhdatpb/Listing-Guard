package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.core.finding.Finding;
import java.util.List;

/**
 * The result of one rule run (ADR-014): the findings, with every fix already verified, and the ids of the rules that
 * failed and were skipped. A run in which a rule failed is incomplete: its findings are real but a clean result is not
 * proof of a clean listing, so a score must not be computed from it as if it were.
 *
 * <p>A run can also be <em>partial</em> (ADR-052): the caller said which fields its data has, and a presence rule left out the others.
 * {@code skippedRuleIds} are the rules that left something out and {@code skippedFields} what they left out, in ruleset order. Nothing
 * failed, but the result says nothing about those fields, so it is never ready and never scored.
 *
 * @param packId the pack of the ruleset that was run, so a consumer can refuse a run of another pack
 */
public record RuleRun(String packId, List<Finding> findings, List<String> failedRuleIds, List<String> skippedRuleIds,
                      List<String> skippedFields) {

    public RuleRun {
        findings = List.copyOf(findings);
        failedRuleIds = List.copyOf(failedRuleIds);
        skippedRuleIds = List.copyOf(skippedRuleIds);
        skippedFields = List.copyOf(skippedFields);
    }

    /** A run in which no field was left out. */
    public RuleRun(String packId, List<Finding> findings, List<String> failedRuleIds) {
        this(packId, findings, failedRuleIds, List.of(), List.of());
    }

    public boolean complete() {
        return failedRuleIds.isEmpty();
    }

    /** True when a rule left a field out because the data never had it (ADR-052). */
    public boolean partial() {
        return !skippedFields.isEmpty();
    }
}
