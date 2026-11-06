package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.TitleWords;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/**
 * The title should not have more words than {@code max_words}. Every word counts, articles and prepositions included
 * ({@link TitleWords}). It is guidance, not a limit Etsy enforces, and there is no fix for it: which words to drop is the
 * seller's choice.
 */
public final class TitleWordCountRule implements Rule {

    public static final String ID = "title_word_count";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        config.rule(ID).intParam("max_words");
    }

    /** The title is measured in words, as {@link TitleWords#count} splits it. */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxWords("title", settings.intParam("max_words")));
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        if (listing.title() == null) {
            return List.of();
        }
        int max = config.rule(ID).intParam("max_words");
        int words = TitleWords.count(listing.title());
        if (words <= max) {
            return List.of();
        }
        return List.of(finding(config, "title", listing.title(),
                "Tiêu đề có " + words + " từ, hướng dẫn của Etsy khuyên tối đa " + max + " từ.",
                "Rút gọn tiêu đề ít nhất " + (words - max) + " từ: nêu rõ món hàng và các đặc điểm quan trọng ngay đầu tiêu đề, "
                        + "phần còn lại để trong mô tả."));
    }
}
