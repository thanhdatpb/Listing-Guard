package com.thanhdatpb.listingguard.core.finding;

/**
 * A single issue found in a listing. Suggestions are advice only; nothing is auto-applied.
 * {@code rulesetVersion} identifies the ruleset that produced the finding. {@code value} is the exact
 * value that caused it (a tag, the title, the matched word), so a client can match by value instead of by
 * position; it is null when there is no single offending value (a missing field, too many tags).
 */
public record Finding(
        String ruleId,
        Severity severity,
        String field,
        String value,
        String message,
        String suggestion,
        String rulesetVersion) {
}
