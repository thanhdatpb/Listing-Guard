package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/**
 * Base of the Amazon rules. Amazon rulesets can override a rule's settings per product type (ADR-006), so a rule
 * works on the {@link RuleSettings} that apply to the listing's product type, and {@code validate} checks the
 * defaults and every override at startup.
 */
abstract class AmazonRule implements Rule {

    @Override
    public final void validate(RulesetConfig config) {
        for (RuleSettings settings : config.rule(id()).withOverrides()) {
            validateSettings(settings);
        }
    }

    @Override
    public final List<Finding> evaluate(Listing listing, RulesetConfig config) {
        return check(listing, config, config.rule(id(), listing.productType()));
    }

    /** Reads every key this rule needs from one set of settings, so a mistake fails at startup. */
    abstract void validateSettings(RuleSettings settings);

    /** @param settings the default settings, or the override of the listing's product type */
    abstract List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings);
}
