package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.Rule;
import java.util.List;

/**
 * The content rules of ADR-039, the same for both marketplaces (the fields they read, and every word list and number, are in the ruleset): six
 * hygiene rules and the four rules that find a fact told two ways in one listing. All are optional: a ruleset published before them does not
 * configure them, so it does not run them and gives the results it always gave.
 */
public final class ContentRules {

    private ContentRules() {
    }

    public static List<Rule> all() {
        return List.of(
                new MojibakeRule(),
                new PlaceholderLeftoverRule(),
                new WhitespaceRule(),
                new UnbalancedBracketsRule(),
                new RepeatedWordRule(),
                new AllCapsRatioRule(),
                FactConflictRule.sizeUnit(),
                FactConflictRule.quantity(),
                FactConflictRule.recipient(),
                new ProductNounConflictRule());
    }
}
