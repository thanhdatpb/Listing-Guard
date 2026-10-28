package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/** At most {@code max_count} bullet points. Fewer, or none, is fine here. */
public final class BulletCountRule extends AmazonRule {

    public static final String ID = "bullet_count";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("max_count");
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        int max = settings.intParam("max_count");
        int count = listing.bulletPoints().size();
        if (count <= max) {
            return List.of();
        }
        return List.of(finding(config, settings, "bullet_points", String.valueOf(count),
                "Listing có " + count + " bullet point, tối đa " + max + ".",
                "Bỏ bớt " + (count - max) + " bullet point, giữ lại các ý quan trọng nhất."));
    }
}
