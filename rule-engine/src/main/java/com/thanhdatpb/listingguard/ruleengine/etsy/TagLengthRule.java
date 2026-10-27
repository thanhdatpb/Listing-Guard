package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;

/** Each tag must not exceed the configured maximum length. */
public final class TagLengthRule implements Rule {

    public static final String ID = "tag_length";

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
        int max = config.rule(ID).intParam("max_length");
        List<Finding> findings = new ArrayList<>();
        List<String> tags = listing.tags();
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            int length = Texts.length(tag);
            if (length > max) {
                findings.add(finding(config, "tags[" + i + "]",
                        "Tag " + Texts.quote(tag) + " dài " + length + " ký tự, tối đa " + max + ".",
                        "Rút ngắn tag này ít nhất " + (length - max) + " ký tự."));
            }
        }
        return findings;
    }
}
