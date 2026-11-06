package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import com.thanhdatpb.listingguard.core.listing.PersonalizationQuestion;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.math.BigDecimal;
import java.util.List;

/** Shared fixtures. Limits are read from the real ruleset so tests never hard-code them. */
final class EtsyTestData {

    static final RulesetConfig CONFIG = RulesetLoader.loadClasspath("rulesets/etsy-2026-12.yaml");

    private EtsyTestData() {
    }

    static int limit(String ruleId, String key) {
        return CONFIG.rule(ruleId).intParam(key);
    }

    /** A listing that satisfies every rule. */
    static Listing valid() {
        return listing("Handmade ceramic mug", List.of("mug", "ceramic"));
    }

    static Listing listing(String title, List<String> tags) {
        return new Listing(title, "A nice mug for tea.", tags, new Money(new BigDecimal("19.99"), "USD"),
                "Home > Mugs", List.of("clay"), List.of("https://example.com/1.jpg"), 5, "i_did", "made_to_order");
    }

    static Listing withDescription(String description) {
        Listing base = valid();
        return new Listing(base.title(), description, base.tags(), base.price(), base.category(), base.materials(),
                base.images(), base.quantity(), base.whoMade(), base.whenMade());
    }

    /** A valid listing with these personalization questions. */
    static Listing withQuestions(PersonalizationQuestion... questions) {
        Listing base = valid();
        return new Listing(base.title(), base.description(), base.tags(), base.price(), base.category(), base.materials(),
                base.images(), base.quantity(), base.whoMade(), base.whenMade(), null, List.of(), null, null, null,
                List.of(questions));
    }

    static PersonalizationQuestion text(String question) {
        return new PersonalizationQuestion(PersonalizationQuestion.TEXT, question, null, null, null, List.of(), null);
    }

    static PersonalizationQuestion dropdown(String question, String... options) {
        return new PersonalizationQuestion(PersonalizationQuestion.DROPDOWN, question, null, null, null, List.of(options), null);
    }

    static PersonalizationQuestion upload(String question, Integer maxFiles, String... labels) {
        return new PersonalizationQuestion(PersonalizationQuestion.UPLOAD, question, null, null, null, List.of(labels), maxFiles);
    }

    static Listing empty() {
        return new Listing(null, null, List.of(), null, null, List.of(), List.of(), null, null, null);
    }
}
