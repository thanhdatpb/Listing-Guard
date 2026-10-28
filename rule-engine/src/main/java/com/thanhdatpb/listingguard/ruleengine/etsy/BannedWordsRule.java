package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags configured words in the title, description and tags. Whole-word, case-insensitive match;
 * one finding per word and field.
 */
public final class BannedWordsRule implements Rule {

    public static final String ID = "banned_words";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        config.rule(ID).stringListParam("words");
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        List<Finding> findings = new ArrayList<>();
        for (String word : config.rule(ID).stringListParam("words")) {
            Pattern pattern = wholeWord(word);
            check(config, findings, pattern, word, "title", listing.title());
            check(config, findings, pattern, word, "description", listing.description());
            List<String> tags = listing.tags();
            for (int i = 0; i < tags.size(); i++) {
                check(config, findings, pattern, word, "tags[" + i + "]", tags.get(i));
            }
        }
        return findings;
    }

    private void check(RulesetConfig config, List<Finding> findings, Pattern pattern, String word, String field, String text) {
        Matcher matcher = text == null ? null : pattern.matcher(text);
        if (matcher != null && matcher.find()) {
            findings.add(finding(config, field, matcher.group(),
                    "Từ '" + word + "' nằm trong danh sách từ cần xem lại và xuất hiện ở " + field + ".",
                    "Xem lại chính sách của sàn và cân nhắc bỏ hoặc thay từ '" + word + "'."));
        }
    }

    private static Pattern wholeWord(String word) {
        return Pattern.compile("(?<![\\p{L}\\p{Nd}])" + Pattern.quote(word.trim()) + "(?![\\p{L}\\p{Nd}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
