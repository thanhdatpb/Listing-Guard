package com.thanhdatpb.listingguard.rag.analysis;

import com.thanhdatpb.listingguard.core.finding.Severity;

/**
 * One policy concern that survived citation verification (ADR-011 item 5). It is not a {@code core.Finding}:
 * the rule engine and the {@code /checks} response do not change.
 *
 * @param ruleId   {@code policy.<topic>}
 * @param severity never {@link Severity#ERROR}: the verifier clamps it (ADR-011 item 7)
 * @param field    the listing text field the concern is about
 * @param value    the offending text as it occurs in {@code field}, or null when the concern is an omission
 * @param message  Vietnamese, at most 400 characters
 * @param suggestion Vietnamese, at most 400 characters
 */
public record PolicyFinding(
        String ruleId,
        Severity severity,
        String field,
        String value,
        String message,
        String suggestion,
        Citation citation) {

    /**
     * Copied from the chunk, not a reference to it: re-ingest replaces chunk ids (ADR-011 item 6).
     *
     * @param sectionPath document title and headings joined with {@code " > "}
     * @param version     the policy document's "Last updated" date
     * @param quote       the model's quote, verified to occur in the cited chunk after normalization
     */
    public record Citation(String sourceFile, String sectionPath, String url, String version, String quote) {
    }
}
