package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;
import java.util.Set;

/** The title may only contain characters matching the configured character class. */
public final class TitleCharactersRule implements Rule {

    public static final String ID = "title_characters";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        Characters.allowedClass(config, ID);
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        if (listing.title() == null) {
            return List.of();
        }
        Set<String> invalid = Characters.invalid(listing.title(), Characters.allowedClass(config, ID));
        if (invalid.isEmpty()) {
            return List.of();
        }
        return List.of(finding(config, "title", listing.title(),
                "Tiêu đề chứa ký tự không hợp lệ: " + String.join(" ", invalid) + ".",
                "Xoá hoặc thay các ký tự không hợp lệ trong tiêu đề."));
    }
}
