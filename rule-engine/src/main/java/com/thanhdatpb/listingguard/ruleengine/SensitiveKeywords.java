package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The logic that the "free text may hold a trademark" reminders of both marketplaces share
 * ({@code personalization_free_text_sensitive} for Etsy, {@code customization_free_text_sensitive} for Amazon): the words
 * come from the ruleset, a word matches ignoring case and only as a whole word, and the rule can never be more than INFO,
 * because it is a hint and no verdict on trademarks. The wording of the messages stays in each rule.
 */
public final class SensitiveKeywords {

    private SensitiveKeywords() {
    }

    /** Refuses a rule rated above INFO, and a {@code keywords} list that is empty or has a blank word. */
    public static void validate(String ruleId, RuleSettings settings) {
        if (settings.severity() != Severity.INFO) {
            throw new RulesetException("Rule '" + ruleId + "' can only be INFO, got " + settings.severity());
        }
        List<String> keywords = settings.stringListParam("keywords");
        if (keywords.isEmpty() || keywords.stream().anyMatch(String::isBlank)) {
            throw new RulesetException("Rule '" + ruleId + "': key 'keywords' must list at least one non-blank word");
        }
    }

    /** The keywords (trimmed, in the order of the list) that {@code text} contains as a whole word, ignoring case. */
    public static List<String> found(String text, List<String> keywords) {
        List<String> found = new ArrayList<>();
        for (String keyword : keywords) {
            if (wholeWord(keyword).matcher(text).find()) {
                found.add(keyword.trim());
            }
        }
        return found;
    }

    private static Pattern wholeWord(String keyword) {
        return Pattern.compile("(?<![\\p{L}\\p{Nd}])" + Pattern.quote(keyword.trim()) + "(?![\\p{L}\\p{Nd}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
