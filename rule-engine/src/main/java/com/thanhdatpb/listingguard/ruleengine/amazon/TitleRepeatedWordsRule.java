package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A word may appear at most {@code max_occurrences} times in the title, ignoring case. Words in
 * {@code exempt_words} (articles, prepositions, conjunctions) are not counted. One finding per word that is over.
 */
public final class TitleRepeatedWordsRule extends AmazonRule {

    public static final String ID = "title_repeated_words";

    /** A word: letters and digits, with inner apostrophes kept so "men's" is one word. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{Nd}]+(?:['’][\\p{L}\\p{Nd}]+)*");

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("max_occurrences");
        settings.stringListParam("exempt_words");
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        if (listing.title() == null) {
            return List.of();
        }
        int max = settings.intParam("max_occurrences");
        Set<String> exempt = new HashSet<>();
        settings.stringListParam("exempt_words").forEach(word -> exempt.add(word.toLowerCase(Locale.ROOT)));

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> firstForm = new LinkedHashMap<>();
        Matcher matcher = WORD.matcher(listing.title());
        while (matcher.find()) {
            String word = matcher.group();
            String key = word.toLowerCase(Locale.ROOT);
            if (exempt.contains(key)) {
                continue;
            }
            counts.merge(key, 1, Integer::sum);
            firstForm.putIfAbsent(key, word);
        }

        List<Finding> findings = new ArrayList<>();
        counts.forEach((key, count) -> {
            if (count > max) {
                String word = firstForm.get(key);
                findings.add(finding(config, settings, "title", word,
                        "Từ " + Texts.quote(word) + " xuất hiện " + count + " lần trong tiêu đề, tối đa " + max + " lần.",
                        "Bỏ bớt " + (count - max) + " lần xuất hiện của từ " + Texts.quote(word) + " trong tiêu đề."));
            }
        });
        return findings;
    }
}
