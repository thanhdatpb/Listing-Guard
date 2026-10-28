package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Each bullet point must not exceed {@code max_length} characters. One finding per bullet that is over; the
 * field is {@code bullet_points[i]}, the zero-based position in the cleaned list.
 */
public final class BulletLengthRule extends AmazonRule {

    public static final String ID = "bullet_length";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("max_length");
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        int max = settings.intParam("max_length");
        List<Finding> findings = new ArrayList<>();
        List<String> bullets = listing.bulletPoints();
        for (int i = 0; i < bullets.size(); i++) {
            String bullet = bullets.get(i);
            int length = Texts.length(bullet);
            if (length > max) {
                findings.add(finding(config, settings, "bullet_points[" + i + "]", bullet,
                        "Bullet point " + Texts.quote(bullet) + " dài " + length + " ký tự, tối đa " + max + ".",
                        "Rút ngắn bullet point này ít nhất " + (length - max) + " ký tự."));
            }
        }
        return findings;
    }
}
