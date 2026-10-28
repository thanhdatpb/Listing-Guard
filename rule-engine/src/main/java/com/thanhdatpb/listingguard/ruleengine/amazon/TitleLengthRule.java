package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/** Title must not exceed the configured maximum length. A missing title is RequiredFields' job. */
public final class TitleLengthRule extends AmazonRule {

    public static final String ID = "title_length";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("max_length");
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        if (listing.title() == null) {
            return List.of();
        }
        int max = settings.intParam("max_length");
        int length = Texts.length(listing.title());
        if (length <= max) {
            return List.of();
        }
        return List.of(finding(config, settings, "title", listing.title(),
                "Tiêu đề dài " + length + " ký tự, tối đa " + max + ".",
                "Rút ngắn tiêu đề ít nhất " + (length - max) + " ký tự."));
    }
}
