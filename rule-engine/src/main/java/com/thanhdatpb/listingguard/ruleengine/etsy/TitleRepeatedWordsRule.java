package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.TitleWords;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * A word may appear at most {@code max_occurrences} times in the title, ignoring case. Words in {@code exempt_words}
 * (articles, prepositions, conjunctions) are not counted. One finding per word that is over. It uses the same word logic as
 * the Amazon rule of the same name ({@link TitleWords}). There is no fix: which occurrence to drop is the seller's choice.
 */
public final class TitleRepeatedWordsRule implements Rule {

    public static final String ID = "title_repeated_words";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        settings.intParam("max_occurrences");
        settings.stringListParam("exempt_words");
    }

    /** How many times the same word (ignoring case, exempt words aside) may appear in the title. */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxOccurrences("title", settings.intParam("max_occurrences")));
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        if (listing.title() == null) {
            return List.of();
        }
        RuleSettings settings = config.rule(ID);
        int max = settings.intParam("max_occurrences");
        List<Finding> findings = new ArrayList<>();
        for (TitleWords.Repeat repeat : TitleWords.repeated(listing.title(), max, settings.stringListParam("exempt_words"))) {
            String word = repeat.word();
            findings.add(finding(config, "title", word,
                    "Từ " + Texts.quote(word) + " xuất hiện " + repeat.count() + " lần trong tiêu đề, tối đa " + max + " lần.",
                    "Bỏ bớt " + (repeat.count() - max) + " lần xuất hiện của từ " + Texts.quote(word) + " trong tiêu đề."));
        }
        return findings;
    }
}
