package com.thanhdatpb.listingguard.trademark.catalog;

import java.util.List;

/**
 * A mark in the catalog.
 *
 * <p>The scanner of PR A1 only reads {@code normalized} and {@code display}. {@code niceClasses}, {@code status} and
 * {@code commonWord} are carried for a later PR that uses them (with a new ruleset version): they do not change any
 * finding today. A catalog that has no such data (the two-argument constructor) leaves them empty, null and false.
 *
 * @param normalized  the mark in match form: the tokens of {@code TextNormalizer.normalizeMark}, one space apart
 * @param display     the mark as the catalog shows it, used in evidence
 * @param niceClasses the Nice classes of the source records, ascending
 * @param status      the status of the source record, null when the catalog has none
 * @param commonWord  the source marks this as a common word
 */
public record CatalogMark(String normalized, String display, List<Integer> niceClasses, MarkStatus status,
                          boolean commonWord) {

    public CatalogMark {
        niceClasses = niceClasses == null ? List.of() : List.copyOf(niceClasses);
    }

    public CatalogMark(String normalized, String display) {
        this(normalized, display, List.of(), null, false);
    }

    /** Number of tokens: a one-token mark is matched as EXACT, a longer one as PHRASE. */
    public int tokenCount() {
        return normalized.isEmpty() ? 0 : normalized.split(" ", -1).length;
    }
}
