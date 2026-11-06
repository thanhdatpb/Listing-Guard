package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.TitleWords;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * A word may appear at most {@code max_occurrences} times in the title, ignoring case. Words in
 * {@code exempt_words} (articles, prepositions, conjunctions) are not counted. One finding per word that is over.
 */
public final class TitleRepeatedWordsRule extends AmazonRule {

    public static final String ID = "title_repeated_words";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("max_occurrences");
        settings.stringListParam("exempt_words");
    }

    /** How many times the same word (ignoring case, exempt words aside) may appear in the title. */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxOccurrences("title", settings.intParam("max_occurrences")));
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        if (listing.title() == null) {
            return List.of();
        }
        int max = settings.intParam("max_occurrences");
        List<Finding> findings = new ArrayList<>();
        for (TitleWords.Repeat repeat : TitleWords.repeated(listing.title(), max, settings.stringListParam("exempt_words"))) {
            String word = repeat.word();
            findings.add(finding(config, settings, "title", word,
                    "Từ " + Texts.quote(word) + " xuất hiện " + repeat.count() + " lần trong tiêu đề, tối đa " + max + " lần.",
                    "Bỏ bớt " + (repeat.count() - max) + " lần xuất hiện của từ " + Texts.quote(word) + " trong tiêu đề."));
        }
        return findings;
    }
}
