package com.thanhdatpb.listingguard.core.finding;

/**
 * A single issue found in a listing. Suggestions are advice only; nothing is auto-applied.
 * {@code rulesetVersion} identifies the ruleset that produced the finding. {@code value} is the exact
 * value that caused it (a tag, the title, the matched word), so a client can match by value instead of by
 * position; it is null when there is no single offending value (a missing field, too many tags).
 *
 * <p>{@code suggestion} is a sentence of advice. {@code fix} is the optional concrete fix for {@code value} (ADR-014):
 * null whenever no fix is certain, which is the common case. Nothing applies a fix; it is a proposal for the seller.
 *
 * <p>{@code matchStart} and {@code matchEnd} (ADR-061) locate {@code value} in the text of {@code field} as the rule read it, in UTF-16 code
 * units, {@code matchStart} inclusive and {@code matchEnd} exclusive. They are null for every rule that does not know where the text
 * is (a length, a count, a missing field) and are never stored or returned by the check API: only the workspace's background audit reads
 * them, from {@code CheckService.evaluate}. Both are set or both are null.
 */
public record Finding(
        String ruleId,
        Severity severity,
        String field,
        String value,
        String message,
        String suggestion,
        String rulesetVersion,
        FixSuggestion fix,
        Integer matchStart,
        Integer matchEnd) {

    public Finding {
        if ((matchStart == null) != (matchEnd == null) || (matchStart != null && (matchStart < 0 || matchEnd <= matchStart))) {
            throw new IllegalArgumentException("matchStart and matchEnd are both null or a non-empty span");
        }
    }

    /** A finding that says no position: the form every finding had before ADR-061. */
    public Finding(String ruleId, Severity severity, String field, String value, String message, String suggestion,
                   String rulesetVersion, FixSuggestion fix) {
        this(ruleId, severity, field, value, message, suggestion, rulesetVersion, fix, null, null);
    }

    /** A finding with no concrete fix; this is the form every finding had before ADR-014. */
    public Finding(String ruleId, Severity severity, String field, String value, String message, String suggestion,
                   String rulesetVersion) {
        this(ruleId, severity, field, value, message, suggestion, rulesetVersion, null);
    }

    /** The same finding without its fix, for a fix that did not survive verification. */
    public Finding withoutFix() {
        return fix == null ? this
                : new Finding(ruleId, severity, field, value, message, suggestion, rulesetVersion, null, matchStart, matchEnd);
    }

    /** The same finding with the span of its {@code value} in the text of its field (ADR-061); an empty span is no position. */
    public Finding withSpan(int start, int end) {
        if (start < 0 || end <= start) {
            return this;
        }
        return new Finding(ruleId, severity, field, value, message, suggestion, rulesetVersion, fix, start, end);
    }
}
