package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/**
 * Search terms must not exceed {@code max_bytes} bytes in UTF-8. Amazon measures this field in bytes, not in
 * characters, so accented letters (2 bytes or more) use up the budget faster than plain ASCII.
 */
public final class SearchTermsLengthRule extends AmazonRule {

    public static final String ID = "search_terms_length";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("max_bytes");
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        if (listing.searchTerms() == null) {
            return List.of();
        }
        int max = settings.intParam("max_bytes");
        int bytes = Texts.utf8Bytes(listing.searchTerms());
        if (bytes <= max) {
            return List.of();
        }
        return List.of(finding(config, settings, "search_terms", listing.searchTerms(),
                "Search terms dài " + bytes + " byte (UTF-8), tối đa " + max + " byte.",
                "Rút ngắn search terms ít nhất " + (bytes - max) + " byte. Ký tự có dấu hoặc ngoài ASCII chiếm từ 2 đến 4 byte mỗi ký tự."));
    }
}
