package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.ruleengine.Rule;
import java.util.List;

/** The Amazon rule set, in the order findings are reported. */
public final class AmazonRules {

    public static final String MARKETPLACE = "AMAZON";

    private AmazonRules() {
    }

    public static List<Rule> all() {
        return List.of(
                new RequiredFieldsRule(),
                new TitleLengthRule(),
                new TitleRepeatedWordsRule(),
                new TitleForbiddenCharsRule(),
                new BulletCountRule(),
                new BulletLengthRule(),
                new SearchTermsLengthRule(),
                new ProhibitedContentRule(),
                new CategoryNotCoveredRule());
    }
}
