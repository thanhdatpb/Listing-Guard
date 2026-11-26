package com.thanhdatpb.listingguard.trademark;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistMatch;
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
 * @param riskAdjustments    why the finding's level is lower than the one its rule assigns, with the original level;
 *                           empty when it is not lower
 * @param sourceLinks        public look-up addresses of the source records, only for a source the ruleset has a
 *                           template for; empty otherwise
 * @param watchlist          the client's watchlist entry the finding comes from (ADR-031); null for any other finding
 */
public record Evidence(String field, int start, int end, String snippet, String normalizedToken, String matchedMark,
                       MatchType matchType, Double score, String catalogVersion, String ruleId,
                       boolean insideColorPhrase, List<String> otherRuleIds, List<String> sourceIds,
                       List<RiskAdjustment> riskAdjustments, List<SourceLink> sourceLinks, WatchlistMatch watchlist,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Long ownerCountSameMark) {

    public Evidence {
        otherRuleIds = List.copyOf(otherRuleIds);
        sourceIds = List.copyOf(sourceIds);
        riskAdjustments = List.copyOf(riskAdjustments);
        sourceLinks = List.copyOf(sourceLinks);
    }

    public Evidence(String field, int start, int end, String snippet, String normalizedToken, String matchedMark,
                    MatchType matchType, Double score, String catalogVersion, String ruleId,
                    boolean insideColorPhrase, List<String> otherRuleIds, List<String> sourceIds,
                    List<RiskAdjustment> riskAdjustments, List<SourceLink> sourceLinks, WatchlistMatch watchlist) {
        this(field, start, end, snippet, normalizedToken, matchedMark, matchType, score, catalogVersion, ruleId,
                insideColorPhrase, otherRuleIds, sourceIds, riskAdjustments, sourceLinks, watchlist, null);
    }

    /** Evidence of a finding that does not come from a watchlist. */
    public Evidence(String field, int start, int end, String snippet, String normalizedToken, String matchedMark,
                    MatchType matchType, Double score, String catalogVersion, String ruleId,
                    boolean insideColorPhrase, List<String> otherRuleIds, List<String> sourceIds,
                    List<RiskAdjustment> riskAdjustments, List<SourceLink> sourceLinks) {
        this(field, start, end, snippet, normalizedToken, matchedMark, matchType, score, catalogVersion, ruleId,
                insideColorPhrase, otherRuleIds, sourceIds, riskAdjustments, sourceLinks, null);
    }

    /** Evidence with no adjustment and no link. */
    public Evidence(String field, int start, int end, String snippet, String normalizedToken, String matchedMark,
                    MatchType matchType, Double score, String catalogVersion, String ruleId,
                    boolean insideColorPhrase, List<String> otherRuleIds, List<String> sourceIds) {
        this(field, start, end, snippet, normalizedToken, matchedMark, matchType, score, catalogVersion, ruleId,
                insideColorPhrase, otherRuleIds, sourceIds, List.of(), List.of());
    }

    /** Evidence of a finding without a catalog mark, so without source ids. */
    public Evidence(String field, int start, int end, String snippet, String normalizedToken, String matchedMark,
                    MatchType matchType, Double score, String catalogVersion, String ruleId,
                    boolean insideColorPhrase, List<String> otherRuleIds) {
        this(field, start, end, snippet, normalizedToken, matchedMark, matchType, score, catalogVersion, ruleId,
                insideColorPhrase, otherRuleIds, List.of(), List.of(), List.of());
    }
}
