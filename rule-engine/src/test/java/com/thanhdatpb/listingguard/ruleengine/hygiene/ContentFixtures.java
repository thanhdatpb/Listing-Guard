package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.math.BigDecimal;
import java.util.List;

/** The listings and the engine of the content-rule tests (ADR-039): the two new rulesets, read from the classpath like the application does. */
final class ContentFixtures {

    static final RulesetConfig ETSY = RulesetLoader.loadClasspath("rulesets/etsy-2026-12b.yaml");
    static final RulesetConfig AMAZON = RulesetLoader.loadClasspath("rulesets/amazon-2026-12d.yaml");
    static final RuleEngine ENGINE = new RuleEngine(RuleRegistry.withDefaults());

    private ContentFixtures() {
    }

    /** An Etsy listing with the given texts; everything else is valid. */
    static Listing etsy(String title, String description, List<String> tags, String category) {
        return new Listing(title, description, tags, new Money(new BigDecimal("19.99"), "USD"), category, List.of("clay"),
                List.of("https://example.com/1.jpg"), 5, "i_did", "made_to_order");
    }

    static Listing etsy(String title, String description) {
        return etsy(title, description, List.of("mug", "ceramic"), "Home > Mugs");
    }

    /** An Amazon listing with the given texts; everything else is valid. */
    static Listing amazon(String title, List<String> bullets, String description, String productType) {
        return new Listing(title, description, List.of(), new Money(new BigDecimal("49.99"), "USD"), null, List.of(),
                List.of("https://example.com/1.jpg"), 5, null, null, "Roadster", bullets, "carry on suitcase", productType);
    }

    static List<Finding> findings(Listing listing, RulesetConfig config, String ruleId) {
        return ENGINE.runDetailed(listing, config).findings().stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    static boolean flagged(Listing listing, RulesetConfig config, String ruleId) {
        return !findings(listing, config, ruleId).isEmpty();
    }
}
