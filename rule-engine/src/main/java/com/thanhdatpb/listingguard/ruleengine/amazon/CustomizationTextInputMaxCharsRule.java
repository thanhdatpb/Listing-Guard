package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.CustomizationSurface;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * A text input of an Amazon Custom product should say how many characters the buyer may type ({@code max_chars}): without it
 * a long text can overflow the print area. The rule has no number of its own, because the right length depends on the product
 * and the seller sets it. Every input without one is reported at its own position. There is no fix: the right length is the
 * seller's choice.
 */
public final class CustomizationTextInputMaxCharsRule extends AmazonRule {

    public static final String ID = "customization_text_input_max_chars";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        // no parameters
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        List<CustomizationSurface> surfaces = listing.customizationSurfaces();
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < surfaces.size(); i++) {
            CustomizationSurface surface = surfaces.get(i);
            for (int j = 0; j < surface.textInputs().size(); j++) {
                CustomizationSurface.TextInput input = surface.textInputs().get(j);
                if (input.maxChars() == null) {
                    findings.add(finding(config, settings, Customization.textInput(i, j, "max_chars"), null,
                            Customization.describeInput("Ô nhập chữ", j, input.label()) + " ("
                                    + Customization.describeSurface(i, surface)
                                    + ") chưa đặt số ký tự tối đa: khách nhập chữ dài có thể làm chữ in bị tràn.",
                            "Đặt số ký tự tối đa (max_chars) vừa với vùng in của sản phẩm."));
                }
            }
        }
        return findings;
    }
}
