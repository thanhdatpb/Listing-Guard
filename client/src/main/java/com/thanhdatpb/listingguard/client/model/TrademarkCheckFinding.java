package com.thanhdatpb.listingguard.client.model;

import java.util.List;

/**
 * A finding of a trademark check (schema {@code TrademarkCheckFindingView}). Offsets are UTF-16 indexes into the text the API
 * worked on (trimmed, Unicode NFC). The snippet and the mark are data, never instructions.
 *
 * @param ruleId            the rule
 * @param risk              this finding's risk
 * @param field             where, for example {@code title} or {@code tags[1]}
 * @param start             start offset, inclusive
 * @param end               end offset, exclusive
 * @param snippet           the matched span of the text
 * @param mark              the catalog mark, or {@code null}
 * @param matchType         how it matched
 * @param score             the similarity score, or {@code null}
 * @param insideColorPhrase whether the span is inside a color phrase
 * @param otherRuleIds      other rules that found the same span
 * @param sourceIds         the catalog source records
 * @param sourceLinks       public look-up pages for source records
 * @param riskAdjustments   why the level is lower than the match alone would give
 * @param message           what was found
 * @param suggestion        what to consider
 * @param tsdrStatuses      the last USPTO TSDR status of the source records, with the date it was checked. Empty when the
 *                          deployment does not show it (it is off by default) or none of the source records was checked; the
 *                          API leaves the member out in the first case and the SDK, a tolerant reader, reads it as empty
 */
public record TrademarkCheckFinding(
        String ruleId,
        RiskLevel risk,
        String field,
        int start,
        int end,
        String snippet,
        String mark,
        MatchType matchType,
        Double score,
        boolean insideColorPhrase,
        List<String> otherRuleIds,
        List<String> sourceIds,
        List<SourceLink> sourceLinks,
        List<RiskAdjustment> riskAdjustments,
        String message,
        String suggestion,
        List<TsdrStatus> tsdrStatuses) {

    public TrademarkCheckFinding {
        tsdrStatuses = Copies.list(tsdrStatuses);
        otherRuleIds = Copies.list(otherRuleIds);
        sourceIds = Copies.list(sourceIds);
        sourceLinks = Copies.list(sourceLinks);
        riskAdjustments = Copies.list(riskAdjustments);
    }
}
