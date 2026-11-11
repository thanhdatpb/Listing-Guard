package com.thanhdatpb.listingguard.trademark;

import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;

/**
 * The versions of the code that decides findings, next to the ruleset. They are part of the analysis fingerprint
 * (ADR-010): a change to any of them must change the fingerprint, so cached results are not reused.
 *
 * @param normalization the {@link TextNormalizer} steps
 * @param matcher       gram generation, exact/phrase/fuzzy matching, context rules, precedence and risk aggregation
 * @param scoring       the final fuzzy score; the ruleset must name the same version (see ADR-009)
 */
public record AlgorithmVersions(String normalization, String matcher, String scoring) {

    /** Optimal string alignment similarity: see {@code Similarity}. */
    public static final String SCORING = "osa-similarity-1";
    /** matcher-2: product classes, generic words and source links (ADR-009); the rules of matcher-1 are unchanged. */
    public static final String MATCHER = "matcher-2";

    public static final AlgorithmVersions CURRENT = new AlgorithmVersions(TextNormalizer.VERSION, MATCHER, SCORING);
}
