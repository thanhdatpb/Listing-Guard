package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Tags may only contain characters matching the configured character class. */
public final class TagCharactersRule implements Rule {

    public static final String ID = "tag_characters";

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
        Pattern allowed = Characters.allowedClass(config, ID);
        List<Finding> findings = new ArrayList<>();
        List<String> tags = listing.tags();
        for (int i = 0; i < tags.size(); i++) {
            Set<String> invalid = Characters.invalid(tags.get(i), allowed);
            if (!invalid.isEmpty()) {
                findings.add(finding(config, "tags[" + i + "]", tags.get(i),
                        "Tag " + Texts.quote(tags.get(i)) + " chứa ký tự không hợp lệ: " + String.join(" ", invalid) + ".",
                        "Xoá hoặc thay các ký tự không hợp lệ trong tag này."));
            }
        }
        return findings;
    }
}
