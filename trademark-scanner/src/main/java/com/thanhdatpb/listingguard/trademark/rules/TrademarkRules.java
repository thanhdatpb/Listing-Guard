package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.util.List;

/** The rules of the pack, in the order the ruleset declares them. */
public final class TrademarkRules {

    private TrademarkRules() {
    }

    public static List<TrademarkRule> create(TrademarkRuleset ruleset) {
        return ruleset.ruleOrder().stream().map(TrademarkRules::rule).toList();
    }

    private static TrademarkRule rule(String id) {
        return switch (id) {
            case RuleIds.CATALOG_EXACT, RuleIds.CATALOG_PHRASE, RuleIds.CATALOG_FUZZY -> new CatalogRule(id);
            case RuleIds.GEO_SPORT_COMBINATION -> new GeoSportRule();
            case RuleIds.COLOR_NAME_CONTAINS_MARK -> new ColorNameContainsMarkRule();
            case RuleIds.PURE_COLOR_DESCRIPTION -> new PureColorDescriptionRule();
            default -> throw new IllegalArgumentException("Unknown rule: " + id);
        };
    }
}
