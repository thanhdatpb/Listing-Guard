package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskAdjustment;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.util.List;
import java.util.Map;

/**
 * A finding before precedence is applied: what one rule found in one field.
 *
 * @param tokenFrom    first token of the span (index in the field's token list)
 * @param tokenTo      last token of the span, inclusive
 * @param start        span start in the canonical field text
 * @param end          span end, exclusive
 * @param mark         the matched catalog mark (display form), null when there is none
 * @param score        1.0 for exact and phrase, the final score for fuzzy, null for a context finding
 * @param placeholders values for the rule's message and suggestion templates
 * @param sourceIds    the ids of the source records of the matched catalog mark, empty when there is no mark
 * @param riskAdjustments why {@code risk} is lower than the level the rule assigns; empty when it is not lower
 */
public record Candidate(String ruleId, RiskLevel risk, String field, int tokenFrom, int tokenTo, int start, int end,
                        String normalizedToken, String mark, MatchType type, Double score,
                        Map<String, String> placeholders, boolean insideColorPhrase, List<String> otherRuleIds,
                        List<String> sourceIds, List<RiskAdjustment> riskAdjustments) {

    public Candidate {
        placeholders = Map.copyOf(placeholders);
        otherRuleIds = List.copyOf(otherRuleIds);
        sourceIds = List.copyOf(sourceIds);
        riskAdjustments = List.copyOf(riskAdjustments);
    }

    /** A candidate whose level is the one its rule assigns. */
    public Candidate(String ruleId, RiskLevel risk, String field, int tokenFrom, int tokenTo, int start, int end,
                     String normalizedToken, String mark, MatchType type, Double score,
                     Map<String, String> placeholders, boolean insideColorPhrase, List<String> otherRuleIds,
                     List<String> sourceIds) {
        this(ruleId, risk, field, tokenFrom, tokenTo, start, end, normalizedToken, mark, type, score, placeholders,
                insideColorPhrase, otherRuleIds, sourceIds, List.of());
    }

    /** A candidate without a catalog mark, so without source ids. */
    public Candidate(String ruleId, RiskLevel risk, String field, int tokenFrom, int tokenTo, int start, int end,
                     String normalizedToken, String mark, MatchType type, Double score,
                     Map<String, String> placeholders, boolean insideColorPhrase, List<String> otherRuleIds) {
        this(ruleId, risk, field, tokenFrom, tokenTo, start, end, normalizedToken, mark, type, score, placeholders,
                insideColorPhrase, otherRuleIds, List.of());
    }

    public Candidate withInsideColorPhrase(boolean inside) {
        return new Candidate(ruleId, risk, field, tokenFrom, tokenTo, start, end, normalizedToken, mark, type, score,
                placeholders, inside, otherRuleIds, sourceIds, riskAdjustments);
    }

    public Candidate withOtherRuleIds(List<String> others) {
        return new Candidate(ruleId, risk, field, tokenFrom, tokenTo, start, end, normalizedToken, mark, type, score,
                placeholders, insideColorPhrase, others, sourceIds, riskAdjustments);
    }

    boolean overlaps(Candidate other) {
        return field.equals(other.field) && start < other.end && other.start < end;
    }
}
