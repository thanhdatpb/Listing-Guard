package com.thanhdatpb.listingguard.ruleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Only the rules of ADR-014 table A3 may carry a fix. A listing that breaks every rule is run through the engine; a rule
 * that is not in the table must never come back with a fix (no replacement words, nothing for trademark or banned
 * words), and every rule in the table must have produced one, so the list below cannot go stale unnoticed.
 */
class FixCoverageTest {

    private static final Set<String> ETSY_FIXABLE = Set.of("title_length", "tag_length", "duplicate_tags");
    private static final Set<String> AMAZON_FIXABLE = Set.of("title_length");

    private static final RuleEngine ENGINE = new RuleEngine(RuleRegistry.withDefaults());

    private static String words(int minLength) {
        StringBuilder text = new StringBuilder("alpha1");
        for (int i = 2; text.length() <= minLength; i++) {
            text.append(" alpha").append(i);
        }
        return text.toString();
    }

    private static Set<String> ruleIds(List<Finding> findings, boolean withFix) {
        return findings.stream().filter(f -> (f.fix() != null) == withFix).map(Finding::ruleId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void etsyRulesOutsideTheTableNeverCarryAFix() {
        RulesetConfig config = RulesetLoader.loadClasspath("rulesets/etsy-2026-11.yaml");
        int titleMax = config.rule("title_length").intParam("max_length");
        int tagMax = config.rule("tag_length").intParam("max_length");
        int tagCount = config.rule("tag_count").intParam("max_count");
        List<String> tags = new java.util.ArrayList<>(List.of("dup", "DUP", "mug " + "x".repeat(tagMax), "bad!tag", "replica"));
        for (int i = 0; tags.size() <= tagCount; i++) {
            tags.add("filler" + i);
        }
        Listing listing = new Listing("replica a & b & c \u0007 " + words(titleMax), null, tags,
                new Money(BigDecimal.ZERO, "USD"), null, List.of(), List.of(), 0, null, null);

        List<Finding> findings = ENGINE.runDetailed(listing, config).findings();

        Set<String> allRules = RuleRegistry.withDefaults().rulesFor("ETSY").stream().map(Rule::id)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(findings.stream().map(Finding::ruleId).collect(Collectors.toSet()))
                .as("the listing breaks every Etsy rule").containsExactlyInAnyOrderElementsOf(allRules);
        assertThat(ruleIds(findings, true)).as("rules that carry a fix").isEqualTo(new TreeSet<>(ETSY_FIXABLE));
    }

    @Test
    void amazonRulesOutsideTheTableNeverCarryAFix() {
        RulesetConfig config = RulesetLoader.loadClasspath("rulesets/amazon-2026-11.yaml");
        int titleMax = config.rule("title_length").intParam("max_length");
        Listing listing = new Listing("Roadster! Roadster! Roadster! www.example.com " + words(titleMax),
                "See https://example.com for more.", List.of(), new Money(new BigDecimal("49.99"), "USD"), null,
                List.of(), List.of(), 5, null, null, "Roadster",
                List.of("one", "two", "three", "four", "five", "six"), "term ".repeat(80), null);

        List<Finding> findings = ENGINE.runDetailed(listing, config).findings();

        Set<String> fired = findings.stream().map(Finding::ruleId).collect(Collectors.toSet());
        assertThat(fired).as("rules this listing breaks").contains(
                "required_fields", "title_length", "title_repeated_words", "title_forbidden_chars", "bullet_count",
                "search_terms_length", "prohibited_content");
        assertThat(ruleIds(findings, true)).as("rules that carry a fix").isEqualTo(new TreeSet<>(AMAZON_FIXABLE));
    }

    @Test
    void aTitleThatBreaksOtherRulesStillGetsItsCutBecauseTheCutAddsNothing() {
        RulesetConfig config = RulesetLoader.loadClasspath("rulesets/amazon-2026-11.yaml");
        int titleMax = config.rule("title_length").intParam("max_length");
        Listing listing = new Listing("Roadster! Roadster! Roadster! " + words(titleMax), "ok", List.of(),
                new Money(new BigDecimal("49.99"), "USD"), null, List.of(), List.of(), 5, null, null, "Roadster",
                List.of("one"), "term", "LUGGAGE");

        Finding title = ENGINE.runDetailed(listing, config).findings().stream().filter(f -> f.ruleId().equals("title_length")).findFirst()
                .orElseThrow();

        assertThat(title.fix()).isNotNull();
    }
}
