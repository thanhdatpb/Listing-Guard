package com.thanhdatpb.listingguard.core.finding;

/**
 * A single issue found in a listing. Suggestions are advice only; nothing is auto-applied.
 * {@code rulesetVersion} identifies the ruleset that produced the finding.
 */
public record Finding(
        String ruleId,
        Severity severity,
        String field,
        String message,
        String suggestion,
        String rulesetVersion) {
}
