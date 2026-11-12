package com.thanhdatpb.listingguard.client.model;

/**
 * A character finding of a personalization screening (schema {@code CharacterFindingView}). It never repeats the buyer's text.
 *
 * @param ruleId       the rule
 * @param severity     how serious it is
 * @param sourceStatus how well the rule is sourced
 * @param kind         the kind of problem
 * @param field        {@code values[i]}
 * @param label        the value's label, or {@code null}
 * @param start        start offset, inclusive
 * @param end          end offset, exclusive
 * @param value        the offending characters made printable (bounded), or {@code null}
 * @param message      what is wrong
 * @param suggestion   what to consider
 */
public record CharacterFinding(
        String ruleId,
        Severity severity,
        SourceStatus sourceStatus,
        String kind,
        String field,
        String label,
        int start,
        int end,
        String value,
        String message,
        String suggestion) {
}
