package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/** Title must not exceed the configured maximum length. A missing title is RequiredFields' job. */
public final class TitleLengthRule implements Rule {

    public static final String ID = "title_length";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        config.rule(ID).intParam("max_length");
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        if (listing.title() == null) {
            return List.of();
        }
        int max = config.rule(ID).intParam("max_length");
        int length = Texts.length(listing.title());
        if (length <= max) {
            return List.of();
        }
        return List.of(finding(config, "title",
                "Tiêu đề dài " + length + " ký tự, tối đa " + max + ".",
                "Rút ngắn tiêu đề ít nhất " + (length - max) + " ký tự."));
    }
}
