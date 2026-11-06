package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/**
 * Item Highlights must not exceed the configured maximum length, counted in characters (code points). A listing
 * without the field is not a finding: it is optional. There is no fix for it (ADR-014 limits fixes to the rules of its
 * table), only a sentence of advice.
 */
public final class ItemHighlightsLengthRule extends AmazonRule {

    public static final String ID = "item_highlights_length";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("max_length");
    }

    /** Item Highlights are measured in code points ({@link Texts#length}). */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxLength("item_highlights", settings.intParam("max_length")));
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        if (listing.itemHighlights() == null) {
            return List.of();
        }
        int max = settings.intParam("max_length");
        int length = Texts.length(listing.itemHighlights());
        if (length <= max) {
            return List.of();
        }
        return List.of(finding(config, settings, "item_highlights", listing.itemHighlights(),
                "Item Highlights dài " + length + " ký tự, tối đa " + max + ".",
                "Rút ngắn Item Highlights ít nhất " + (length - max) + " ký tự."));
    }
}
