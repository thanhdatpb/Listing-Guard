package com.thanhdatpb.listingguard.client.model;

import java.util.List;

/**
 * A trademark finding of a personalization screening (schema {@code TrademarkFindingView}). Unlike
 * {@link TrademarkCheckFinding} it has a {@code label} and no {@code snippet}: the buyer's text is never repeated.
 *
 * @param ruleId            the rule
 * @param risk              this finding's risk
 * @param field             {@code personalization[i]}
 * @param label             the value's label, or {@code null}
 * @param start             start offset, inclusive
 * @param end               end offset, exclusive
 * @param mark              the catalog mark, or {@code null}
 * @param matchType         how it matched
 * @param score             the similarity score, or {@code null}
 * @param insideColorPhrase whether the span is inside a color phrase
 * @param otherRuleIds      other rules that found the same span
 * @param sourceIds         the catalog source records
 * @param message           what was found
 * @param suggestion        what to consider
 */
public record PersonalizationTrademarkFinding(
        String ruleId,
        RiskLevel risk,
        String field,
        String label,
        int start,
        int end,
        String mark,
        MatchType matchType,
        Double score,
        boolean insideColorPhrase,
        List<String> otherRuleIds,
        List<String> sourceIds,
        String message,
        String suggestion) {

    public PersonalizationTrademarkFinding {
        otherRuleIds = Copies.list(otherRuleIds);
        sourceIds = Copies.list(sourceIds);
    }
}
