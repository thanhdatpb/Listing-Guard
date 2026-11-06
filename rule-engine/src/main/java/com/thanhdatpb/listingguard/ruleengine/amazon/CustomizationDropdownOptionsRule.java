package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.CustomizationSurface;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The dropdown options of an Amazon Custom product: each print surface may have at most {@code max_count} options, counted
 * over all its dropdowns together, and no label may appear twice in the same dropdown. Two labels are the same when they are
 * equal, ignoring case only if {@code case_insensitive} is set. A blank option is dropped by the normalizer. There is no fix:
 * which option to drop or rename is the seller's choice.
 */
public final class CustomizationDropdownOptionsRule extends AmazonRule {

    public static final String ID = "customization_dropdown_options";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("max_count");
        settings.boolParam("case_insensitive");
    }

    /** The options of all the dropdowns of one surface, as items of the cleaned lists. */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxCount(Customization.DROPDOWN_OPTIONS, settings.intParam("max_count")));
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        int max = settings.intParam("max_count");
        boolean ignoreCase = settings.boolParam("case_insensitive");
        List<CustomizationSurface> surfaces = listing.customizationSurfaces();
        List<Finding> findings = new ArrayList<>();

        for (int i = 0; i < surfaces.size(); i++) {
            CustomizationSurface surface = surfaces.get(i);
            String surfaceName = Customization.describeSurface(i, surface);
            int total = surface.dropdowns().stream().mapToInt(dropdown -> dropdown.options().size()).sum();
            if (total > max) {
                findings.add(finding(config, settings, Customization.field(i, "dropdowns"), null,
                        surfaceName + " có " + total + " lựa chọn dropdown (cộng các dropdown), tối đa " + max + ".",
                        "Bỏ bớt " + (total - max) + " lựa chọn dropdown."));
            }
            for (int j = 0; j < surface.dropdowns().size(); j++) {
                CustomizationSurface.Dropdown dropdown = surface.dropdowns().get(j);
                String dropdownName = Customization.describeInput("dropdown", j, dropdown.label());
                Map<String, Integer> firstSeenAt = new HashMap<>();
                for (int k = 0; k < dropdown.options().size(); k++) {
                    String option = dropdown.options().get(k);
                    Integer first = firstSeenAt.putIfAbsent(ignoreCase ? option.toLowerCase(Locale.ROOT) : option, k);
                    if (first != null) {
                        findings.add(finding(config, settings, Customization.option(i, j, k), option,
                                "Lựa chọn " + Texts.quote(option) + " của " + dropdownName + " (" + surfaceName
                                        + ") trùng với lựa chọn ở vị trí " + (first + 1)
                                        + ", các lựa chọn của một dropdown phải khác nhau.",
                                "Xoá lựa chọn trùng hoặc đổi nhãn để các lựa chọn khác nhau."));
                    }
                }
            }
        }
        return findings;
    }
}
