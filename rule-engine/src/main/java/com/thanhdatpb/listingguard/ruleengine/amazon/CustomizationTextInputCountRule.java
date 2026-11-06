package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/**
 * An Amazon Custom product may have at most {@code max_count} text inputs, counted over all its print surfaces together. A
 * product without customization is not a finding. There is no fix: which input to drop is the seller's choice.
 */
public final class CustomizationTextInputCountRule extends AmazonRule {

    public static final String ID = "customization_text_input_count";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("max_count");
    }

    /** The text inputs of all surfaces, as items of the cleaned lists. */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxCount(Customization.TEXT_INPUTS, settings.intParam("max_count")));
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        int max = settings.intParam("max_count");
        int total = listing.customizationSurfaces().stream().mapToInt(surface -> surface.textInputs().size()).sum();
        if (total <= max) {
            return List.of();
        }
        return List.of(finding(config, settings, Customization.TEXT_INPUTS, null,
                "Sản phẩm tùy chỉnh có " + total + " ô nhập chữ (cộng các bề mặt in), tối đa " + max + ".",
                "Bỏ bớt " + (total - max) + " ô nhập chữ."));
    }
}
