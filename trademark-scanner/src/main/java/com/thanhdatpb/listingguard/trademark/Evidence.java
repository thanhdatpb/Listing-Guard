package com.thanhdatpb.listingguard.trademark;

import java.util.List;

/**
 * Why a finding exists (ADR-009).
 *
 * @param field              {@code title}, {@code tags[2]}, {@code description}, ...
 * @param start              start of the span in the canonical text of the field (UTF-16 code units), inclusive
 * @param end                end of the span, exclusive
 * @param snippet            the canonical text of the span, cut to the ruleset's length limit
 * @param normalizedToken    the normalized token or n-gram the match used
 * @param matchedMark        the catalog mark that matched, null for a finding that has no mark
 * @param matchType          how the finding was found
 * @param score              1.0 for an exact or phrase match, the final score for a fuzzy one, null otherwise
 * @param catalogVersion     the catalog version the scan used
 * @param ruleId             the rule that produced the finding
 * @param insideColorPhrase  the match lies inside a color phrase; this explains the finding and never lowers it
 * @param otherRuleIds       other rules that matched an overlapping span of the same field and lost to this one
 * @param sourceIds          the ids of the source records of the matched catalog mark, empty for a finding without a mark
 */
public record Evidence(String field, int start, int end, String snippet, String normalizedToken, String matchedMark,
                       MatchType matchType, Double score, String catalogVersion, String ruleId,
                       boolean insideColorPhrase, List<String> otherRuleIds, List<String> sourceIds) {

    public Evidence {
        otherRuleIds = List.copyOf(otherRuleIds);
        sourceIds = List.copyOf(sourceIds);
    }

    /** Evidence of a finding without a catalog mark, so without source ids. */
    public Evidence(String field, int start, int end, String snippet, String normalizedToken, String matchedMark,
                    MatchType matchType, Double score, String catalogVersion, String ruleId,
                    boolean insideColorPhrase, List<String> otherRuleIds) {
        this(field, start, end, snippet, normalizedToken, matchedMark, matchType, score, catalogVersion, ruleId,
                insideColorPhrase, otherRuleIds, List.of());
    }
}
