package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/**
 * The description must not exceed the configured maximum length, counted in characters (code points). A listing without a
 * description is not a finding here: whether it is required is not a rule of this ruleset. There is no fix: the number is
 * unverified and cutting a description drops content the seller wrote, so only a sentence of advice is given (ADR-014
 * limits fixes to the rules of its table; {@code FixCoverageTest} holds the list).
 */
public final class DescriptionLengthRule extends AmazonRule {

    public static final String ID = "description_length";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("max_length");
    }

    /** The description is measured in code points ({@link Texts#length}). */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxLength("description", settings.intParam("max_length")));
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        if (listing.description() == null) {
            return List.of();
        }
        int max = settings.intParam("max_length");
        int length = Texts.length(listing.description());
        if (length <= max) {
            return List.of();
        }
        return List.of(finding(config, settings, "description", listing.description(),
                "Mô tả dài " + length + " ký tự, tối đa " + max + ".",
                "Rút ngắn mô tả ít nhất " + (length - max) + " ký tự."));
    }
}
