package com.thanhdatpb.listingguard.core.finding;

/**
 * A single issue found in a listing. Suggestions are advice only; nothing is auto-applied.
 * {@code rulesetVersion} identifies the ruleset that produced the finding. {@code value} is the exact
 * value that caused it (a tag, the title, the matched word), so a client can match by value instead of by
 * position; it is null when there is no single offending value (a missing field, too many tags).
 *
 * <p>{@code suggestion} is a sentence of advice. {@code fix} is the optional concrete fix for {@code value} (ADR-014):
 * null whenever no fix is certain, which is the common case. Nothing applies a fix; it is a proposal for the seller.
 */
public record Finding(
        String ruleId,
        Severity severity,
        String field,
        String value,
        String message,
        String suggestion,
        String rulesetVersion,
        FixSuggestion fix) {

    /** A finding with no concrete fix; this is the form every finding had before ADR-014. */
    public Finding(String ruleId, Severity severity, String field, String value, String message, String suggestion,
                   String rulesetVersion) {
        this(ruleId, severity, field, value, message, suggestion, rulesetVersion, null);
    }

    /** The same finding without its fix, for a fix that did not survive verification. */
    public Finding withoutFix() {
        return fix == null ? this : new Finding(ruleId, severity, field, value, message, suggestion, rulesetVersion, null);
    }
}
