package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.math.BigDecimal;
import java.util.List;

/** Shared fixtures. Limits are read from the real ruleset so tests never hard-code them. */
final class AmazonTestData {

    static final RulesetConfig CONFIG = RulesetLoader.loadClasspath("rulesets/amazon-2026-11.yaml");

    private AmazonTestData() {
    }

    static int limit(String ruleId, String key) {
        return CONFIG.rule(ruleId).intParam(key);
    }

    /** A listing that satisfies every rule of the ruleset. */
    static Listing valid() {
        return listing("Hardside carry-on luggage with spinner wheels", "Roadster",
                List.of("Lightweight hardside shell", "Four spinner wheels"), "A durable suitcase.", "carry on suitcase", "LUGGAGE");
    }

    static Listing listing(String title, String brand, List<String> bulletPoints, String description, String searchTerms,
                           String productType) {
        return new Listing(title, description, List.of(), new Money(new BigDecimal("49.99"), "USD"), null, List.of(),
                List.of("https://example.com/1.jpg"), 5, null, null, brand, bulletPoints, searchTerms, productType);
    }

    static Listing withTitle(String title) {
        Listing base = valid();
        return listing(title, base.brand(), base.bulletPoints(), base.description(), base.searchTerms(), base.productType());
    }

    static Listing withBrand(String title, String brand) {
        Listing base = valid();
        return listing(title, brand, base.bulletPoints(), base.description(), base.searchTerms(), base.productType());
    }

    static Listing withBullets(List<String> bulletPoints) {
        Listing base = valid();
        return listing(base.title(), base.brand(), bulletPoints, base.description(), base.searchTerms(), base.productType());
    }

    static Listing withSearchTerms(String searchTerms) {
        Listing base = valid();
        return listing(base.title(), base.brand(), base.bulletPoints(), base.description(), searchTerms, base.productType());
    }

    static Listing withDescription(String description) {
        Listing base = valid();
        return listing(base.title(), base.brand(), base.bulletPoints(), description, base.searchTerms(), base.productType());
    }

    static Listing withProductType(String productType) {
        Listing base = valid();
        return listing(base.title(), base.brand(), base.bulletPoints(), base.description(), base.searchTerms(), productType);
    }

    static Listing empty() {
        return new Listing(null, null, List.of(), null, null, List.of(), List.of(), null, null, null, null, List.of(), null, null);
    }
}
