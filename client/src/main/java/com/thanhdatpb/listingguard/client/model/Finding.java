package com.thanhdatpb.listingguard.client.model;

/**
 * A finding of a listing check or a variation check (schema {@code FindingView}). Decide on {@code ruleId}, {@code field},
 * {@code value} and {@code severity}; {@code message} and {@code suggestion} are text for people (Vietnamese today).
 *
 * @param ruleId     the rule's stable id, for example {@code tag_length}
 * @param source     where the finding comes from
 * @param severity   how serious it is
 * @param field      where the problem is, for example {@code tags[2]}
 * @param value      the exact value that caused it, or {@code null} when no single value applies
 * @param message    what is wrong
 * @param suggestion what to consider
 * @param fix        a verified fix suggestion, or {@code null}; advice that Listing Guard never applies
 */
public record Finding(
        String ruleId,
        FindingSource source,
        Severity severity,
        String field,
        String value,
        String message,
        String suggestion,
        Fix fix) {
}
