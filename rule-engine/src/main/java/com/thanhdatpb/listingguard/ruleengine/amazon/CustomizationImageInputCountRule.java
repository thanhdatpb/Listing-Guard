package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.CustomizationSurface;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Each print surface of an Amazon Custom product may have at most {@code max_count} image inputs (places where the buyer
 * uploads an image). Every surface over the limit is reported once, at its own position. There is no fix.
 */
public final class CustomizationImageInputCountRule extends AmazonRule {

    public static final String ID = "customization_image_input_count";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("max_count");
    }

    /** The image inputs of one surface, as items of the cleaned list. */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxCount(Customization.IMAGE_INPUTS, settings.intParam("max_count")));
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        int max = settings.intParam("max_count");
        List<CustomizationSurface> surfaces = listing.customizationSurfaces();
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < surfaces.size(); i++) {
            CustomizationSurface surface = surfaces.get(i);
            int count = surface.imageInputs().size();
            if (count > max) {
                findings.add(finding(config, settings, Customization.field(i, "image_inputs"), null,
                        Customization.describeSurface(i, surface) + " có " + count + " ô tải ảnh lên, tối đa " + max + ".",
                        "Bỏ bớt " + (count - max) + " ô tải ảnh lên."));
            }
        }
        return findings;
    }
}
