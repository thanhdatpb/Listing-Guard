package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/** A listing must not have more tags than the configured maximum. */
public final class TagCountRule implements Rule {

    public static final String ID = "tag_count";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        config.rule(ID).intParam("max_count");
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        int max = config.rule(ID).intParam("max_count");
        int count = listing.tags().size();
        if (count <= max) {
            return List.of();
        }
        return List.of(finding(config, "tags",
                "Listing có " + count + " tag, tối đa " + max + ".",
                "Bỏ bớt " + (count - max) + " tag, giữ lại các tag liên quan nhất."));
    }
}
