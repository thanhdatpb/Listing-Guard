package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.List;

/** Each configured special character may appear in the title at most the configured number of times. */
public final class TitleRepeatedSpecialCharsRule implements Rule {

    public static final String ID = "title_repeated_special_chars";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        config.rule(ID).intParam("max_occurrences");
        for (String character : config.rule(ID).stringListParam("chars")) {
            if (character.codePointCount(0, character.length()) != 1) {
                throw new RulesetException("Rule '" + ID + "': each entry of 'chars' must be one character, got '" + character + "'");
            }
        }
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        if (listing.title() == null) {
            return List.of();
        }
        int max = config.rule(ID).intParam("max_occurrences");
        List<Finding> findings = new ArrayList<>();
        for (String character : config.rule(ID).stringListParam("chars")) {
            int codePoint = character.codePointAt(0);
            long count = listing.title().codePoints().filter(c -> c == codePoint).count();
            if (count > max) {
                findings.add(finding(config, "title", character,
                        "Ký tự '" + character + "' xuất hiện " + count + " lần trong tiêu đề, tối đa " + max + " lần.",
                        "Chỉ giữ " + max + " ký tự '" + character + "' trong tiêu đề, bỏ " + (count - max) + " ký tự còn lại."));
            }
        }
        return findings;
    }
}
