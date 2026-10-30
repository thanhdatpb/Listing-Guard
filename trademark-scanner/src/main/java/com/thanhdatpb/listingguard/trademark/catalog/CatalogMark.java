package com.thanhdatpb.listingguard.trademark.catalog;

/**
 * A mark in the catalog.
 *
 * @param normalized the mark in match form: the tokens of {@code TextNormalizer.normalizeMark}, one space apart
 * @param display    the mark as the catalog shows it, used in evidence
 */
public record CatalogMark(String normalized, String display) {

    /** Number of tokens: a one-token mark is matched as EXACT, a longer one as PHRASE. */
    public int tokenCount() {
        return normalized.isEmpty() ? 0 : normalized.split(" ", -1).length;
    }
}
