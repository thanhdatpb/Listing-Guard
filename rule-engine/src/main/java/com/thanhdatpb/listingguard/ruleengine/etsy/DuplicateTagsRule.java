package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reports every repeat of an earlier tag; the first occurrence is not flagged. */
public final class DuplicateTagsRule implements Rule {

    public static final String ID = "duplicate_tags";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        config.rule(ID).boolParam("case_insensitive");
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        boolean ignoreCase = config.rule(ID).boolParam("case_insensitive");
        Map<String, Integer> firstSeenAt = new HashMap<>();
        List<Finding> findings = new ArrayList<>();
        List<String> tags = listing.tags();
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            String key = ignoreCase ? tag.toLowerCase(Locale.ROOT) : tag;
            Integer first = firstSeenAt.putIfAbsent(key, i);
            if (first != null) {
                findings.add(finding(config, "tags[" + i + "]", tag,
                        "Tag " + Texts.quote(tag) + " trùng với tag ở vị trí " + (first + 1) + ".",
                        "Xoá tag trùng hoặc thay bằng một tag khác để tận dụng đủ chỗ."));
            }
        }
        return findings;
    }
}
