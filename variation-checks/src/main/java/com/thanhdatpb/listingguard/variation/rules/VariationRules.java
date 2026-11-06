package com.thanhdatpb.listingguard.variation.rules;

import com.thanhdatpb.listingguard.variation.config.RuleIds;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import java.util.ArrayList;
import java.util.List;

/** Builds the rules of a ruleset, in the order the ruleset declares them (ADR-015). */
public final class VariationRules {

    private VariationRules() {
    }

    public static List<VariationRule> create(VariationRuleset ruleset) {
        List<VariationRule> rules = new ArrayList<>();
        for (VariationRuleset.RuleSpec spec : ruleset.rules()) {
            rules.add(switch (spec.id()) {
                case RuleIds.PARENT_CHILD_STRUCTURE -> new ParentChildStructureRule(ruleset);
                case RuleIds.VARIATION_THEME_ALLOWED -> new VariationThemeAllowedRule(ruleset);
                case RuleIds.APPAREL_SIZE_ATTRIBUTES -> new ApparelSizeAttributesRule(ruleset);
                case RuleIds.DUPLICATE_COMBINATION -> new DuplicateCombinationRule(ruleset);
                case RuleIds.DUPLICATE_SKU -> new DuplicateSkuRule(ruleset);
                case RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME -> new ChildrenDifferOutsideThemeRule(ruleset);
                case RuleIds.ETSY_VARIATION_LIMITS -> new EtsyVariationLimitsRule(ruleset);
                default -> throw new IllegalStateException("No implementation for rule " + spec.id());
            });
        }
        return rules;
    }
}
