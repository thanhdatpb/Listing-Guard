package com.thanhdatpb.listingguard.ruleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import com.thanhdatpb.listingguard.core.listing.PersonalizationQuestion;
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

    /** Distinct words that are each long enough for a title cut at {@code titleMax} to stay under {@code maxWords} words. */
    private static String longWords(int minLength, int titleMax, int maxWords) {
        int padding = titleMax / maxWords + 2;
        StringBuilder text = new StringBuilder();
        for (int i = 1; text.length() <= minLength; i++) {
            text.append(i > 1 ? " " : "").append("w".repeat(padding)).append(i);
        }
        return text.toString();
    }

    private static String words(int minLength) {
        StringBuilder text = new StringBuilder("alpha1");
        for (int i = 2; text.length() <= minLength; i++) {
            text.append(" alpha").append(i);
        }
        return text.toString();
    }

    /** Personalization questions that break every personalization rule once, with the numbers read from the ruleset. */
    private static List<PersonalizationQuestion> brokenQuestions(RulesetConfig config) {
        int maxQuestions = config.rule("personalization_question_count").intParam("max_count");
        int maxText = config.rule("personalization_question_text").intParam("max_length");
        int maxOptions = config.rule("personalization_dropdown_options").intParam("max_count");
        int maxLabel = config.rule("personalization_dropdown_options").intParam("max_length");
        int maxFiles = config.rule("personalization_upload_files").intParam("max_files");
        int maxInstructions = config.rule("personalization_instructions_length").intParam("max_length");

        List<String> options = new java.util.ArrayList<>(List.of("Dup", "Dup", "x".repeat(maxLabel + 1)));
        for (int i = 0; options.size() <= maxOptions; i++) {
            options.add("Option " + i);
        }
        List<PersonalizationQuestion> questions = new java.util.ArrayList<>(List.of(
                new PersonalizationQuestion("TEXT", "Player name", "x".repeat(maxInstructions + 1), true, 20, List.of(), null),
                new PersonalizationQuestion("TEXT", "x".repeat(maxText + 1), null, null, null, List.of(), null),
                new PersonalizationQuestion("DROPDOWN", "Font", null, null, null, options, null),
                new PersonalizationQuestion("UPLOAD", "Photo A", null, null, null, List.of("Front"), 1),
                new PersonalizationQuestion("UPLOAD", "Photo B", null, null, null, List.of(), maxFiles + 1),
                new PersonalizationQuestion("TEXT", null, null, null, null, List.of(), null)));
        assertThat(questions.size()).as("more questions than allowed").isGreaterThan(maxQuestions);
        return questions;
    }

    private static Set<String> ruleIds(List<Finding> findings, boolean withFix) {
        return findings.stream().filter(f -> (f.fix() != null) == withFix).map(Finding::ruleId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void etsyRulesOutsideTheTableNeverCarryAFix() {
        RulesetConfig config = RulesetLoader.loadClasspath("rulesets/etsy-2026-12.yaml");
        int titleMax = config.rule("title_length").intParam("max_length");
        int tagMax = config.rule("tag_length").intParam("max_length");
        int tagCount = config.rule("tag_count").intParam("max_count");
        int maxWords = config.rule("title_word_count").intParam("max_words");
        List<String> tags = new java.util.ArrayList<>(List.of("dup", "DUP", "mug " + "x".repeat(tagMax), "bad!tag", "replica"));
        for (int i = 0; tags.size() <= tagCount; i++) {
            tags.add("filler" + i);
        }
        List<PersonalizationQuestion> questions = brokenQuestions(config);
        String title = "replica sale a & b & c mug mug \u0007 " + longWords(titleMax, titleMax, maxWords) + " "
                + words(maxWords * 8);
        Listing listing = new Listing(title, null, tags, new Money(BigDecimal.ZERO, "USD"), null, List.of(), List.of(), 0,
                null, null, null, List.of(), null, null, null, questions);

        List<Finding> findings = ENGINE.runDetailed(listing, config).findings();

        Set<String> enabledRules = RuleRegistry.withDefaults().rulesFor("ETSY").stream().map(Rule::id)
                .filter(id -> config.rule(id).enabled()).collect(Collectors.toCollection(TreeSet::new));
        assertThat(enabledRules).as("banned_words is switched off in this ruleset").doesNotContain("banned_words");
        assertThat(findings.stream().map(Finding::ruleId).collect(Collectors.toSet()))
                .as("the listing breaks every enabled Etsy rule").containsExactlyInAnyOrderElementsOf(enabledRules);
        assertThat(ruleIds(findings, true)).as("rules that carry a fix").isEqualTo(new TreeSet<>(ETSY_FIXABLE));
    }

    @Test
    void amazonRulesOutsideTheTableNeverCarryAFix() {
        RulesetConfig config = RulesetLoader.loadClasspath("rulesets/amazon-2026-12.yaml");
        int titleMax = config.rule("title_length").intParam("max_length");
        int highlightsMax = config.rule("item_highlights_length").intParam("max_length");
        Listing listing = new Listing("Roadster! Roadster! Roadster! www.example.com " + words(titleMax),
                "See https://example.com for more.", List.of(), new Money(new BigDecimal("49.99"), "USD"), null,
                List.of(), List.of(), 5, null, null, "Roadster",
                List.of("one", "two", "three", "four", "five", "six"), "term ".repeat(80), null,
                words(highlightsMax));

        List<Finding> findings = ENGINE.runDetailed(listing, config).findings();

        Set<String> fired = findings.stream().map(Finding::ruleId).collect(Collectors.toSet());
        assertThat(fired).as("rules this listing breaks").contains(
                "required_fields", "title_length", "title_repeated_words", "title_forbidden_chars", "bullet_count",
                "item_highlights_length", "search_terms_length", "prohibited_content");
        assertThat(ruleIds(findings, true)).as("rules that carry a fix").isEqualTo(new TreeSet<>(AMAZON_FIXABLE));
    }

    @Test
    void aTitleThatBreaksOtherRulesStillGetsItsCutBecauseTheCutAddsNothing() {
        RulesetConfig config = RulesetLoader.loadClasspath("rulesets/amazon-2026-12.yaml");
        int titleMax = config.rule("title_length").intParam("max_length");
        Listing listing = new Listing("Roadster! Roadster! Roadster! " + words(titleMax), "ok", List.of(),
                new Money(new BigDecimal("49.99"), "USD"), null, List.of(), List.of(), 5, null, null, "Roadster",
                List.of("one"), "term", "LUGGAGE");

        Finding title = ENGINE.runDetailed(listing, config).findings().stream().filter(f -> f.ruleId().equals("title_length")).findFirst()
                .orElseThrow();

        assertThat(title.fix()).isNotNull();
    }
}
