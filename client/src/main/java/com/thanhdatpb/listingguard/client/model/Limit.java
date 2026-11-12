package com.thanhdatpb.listingguard.client.model;

/**
 * One numeric limit of a ruleset (schema {@code LimitView}).
 *
 * @param field        the field it applies to, for example {@code title}
 * @param ruleId       the rule that enforces it
 * @param kind         what it bounds
 * @param value        the number
 * @param unit         what it counts
 * @param severity     the severity of a finding over it
 * @param sourceStatus how well the number is sourced
 * @param enabled      false for a rule that is switched off
 */
public record Limit(
        String field,
        String ruleId,
        LimitKind kind,
        int value,
        LimitUnit unit,
        Severity severity,
        SourceStatus sourceStatus,
        boolean enabled) {
}
