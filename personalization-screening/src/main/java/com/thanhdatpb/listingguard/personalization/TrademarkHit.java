package com.thanhdatpb.listingguard.personalization;

import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.util.List;

/**
 * One finding of the trademark scanner, read back for a value (ADR-016). It never carries the buyer's text: no snippet and
 * no token. {@code mark} is the catalog's display form (catalog data, null for a context finding). {@code start} and
 * {@code end} are UTF-16 code units in the value's text as sent, {@code end} exclusive. {@code message} and
 * {@code suggestion} come from the personalization ruleset's own templates.
 */
public record TrademarkHit(String ruleId, RiskLevel risk, int valueIndex, String field, String label, int start, int end,
                           MatchType matchType, String mark, Double score, boolean insideColorPhrase,
                           List<String> otherRuleIds, List<String> sourceIds, String message, String suggestion) {

    public TrademarkHit {
        otherRuleIds = List.copyOf(otherRuleIds);
        sourceIds = List.copyOf(sourceIds);
    }
}
