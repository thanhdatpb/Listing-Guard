package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.ruleengine.Rule;
import java.util.List;

/** The Etsy rule set, in the order findings are reported. */
public final class EtsyRules {

    public static final String MARKETPLACE = "ETSY";

    private EtsyRules() {
    }

    public static List<Rule> all() {
        return List.of(
                new RequiredFieldsRule(),
                new TitleLengthRule(),
                new TagCountRule(),
                new TagLengthRule(),
                new TagCharactersRule(),
                new DuplicateTagsRule(),
                new BannedWordsRule());
    }
}
