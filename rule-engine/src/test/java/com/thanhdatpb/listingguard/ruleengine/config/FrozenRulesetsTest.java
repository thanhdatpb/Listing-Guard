package com.thanhdatpb.listingguard.ruleengine.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A published ruleset version is immutable (ADR-002). These hashes pin the files that have already
 * been registered in databases: if one of these tests fails, someone edited a published ruleset.
 * Make the change in a new version instead.
 */
class FrozenRulesetsTest {

    @Test
    void etsy202610IsUnchanged() {
        RulesetConfig config = RulesetLoader.loadClasspath("rulesets/etsy-2026-10.yaml");

        assertThat(config.version()).isEqualTo("etsy-2026-10");
        assertThat(config.contentHash())
                .isEqualTo("bf702fda66f7e1c20ceda9c7a65f868ac46ab12a63d7fdf32a88b4965a6c5211");
    }

    @Test
    void etsy202611IsUnchanged() {
        RulesetConfig config = RulesetLoader.loadClasspath("rulesets/etsy-2026-11.yaml");

        assertThat(config.version()).isEqualTo("etsy-2026-11");
        assertThat(config.contentHash())
                .isEqualTo("97913da09c1eb2b79d419a60a89ff7ca8e1beafa2b5abb246dbc16995c163da8");
    }

    @Test
    void amazon202611IsUnchanged() {
        RulesetConfig config = RulesetLoader.loadClasspath("rulesets/amazon-2026-11.yaml");

        assertThat(config.version()).isEqualTo("amazon-2026-11");
        assertThat(config.contentHash())
                .isEqualTo("9ede48e885e91644521dd9568b2cfbf9a292954a063bf9f52d5fe9d707509063");
    }

    @Test
    void amazon202612IsADifferentVersionThatKeepsEveryRuleOfTheOldOne() {
        RulesetConfig old = RulesetLoader.loadClasspath("rulesets/amazon-2026-11.yaml");
        RulesetConfig current = RulesetLoader.loadClasspath("rulesets/amazon-2026-12.yaml");

        assertThat(current.version()).isEqualTo("amazon-2026-12");
        assertThat(current.contentHash()).isNotEqualTo(old.contentHash());
        assertThat(current.rules().keySet()).containsAll(old.rules().keySet()).contains("item_highlights_length");
    }

    @Test
    void etsy202611IsADifferentVersionWithDifferentContent() {
        RulesetConfig old = RulesetLoader.loadClasspath("rulesets/etsy-2026-10.yaml");
        RulesetConfig current = RulesetLoader.loadClasspath("rulesets/etsy-2026-11.yaml");

        assertThat(current.version()).isEqualTo("etsy-2026-11");
        assertThat(current.contentHash()).isNotEqualTo(old.contentHash());
        assertThat(current.rules().keySet()).containsAll(old.rules().keySet());
    }

    @Test
    void etsy202612IsADifferentVersionThatKeepsEveryRuleOfTheOldOneAndAddsTheTitleGuidance() {
        RulesetConfig old = RulesetLoader.loadClasspath("rulesets/etsy-2026-11.yaml");
        RulesetConfig current = RulesetLoader.loadClasspath("rulesets/etsy-2026-12.yaml");

        assertThat(current.version()).isEqualTo("etsy-2026-12");
        assertThat(current.contentHash()).isNotEqualTo(old.contentHash());
        assertThat(current.rules().keySet()).containsAll(old.rules().keySet())
                .contains("title_word_count", "title_repeated_words", "title_promotional_terms");
    }

    @Test
    void etsy202612SwitchesBannedWordsOffAndChangesNothingElseOfTheRulesItKeeps() {
        RulesetConfig old = RulesetLoader.loadClasspath("rulesets/etsy-2026-11.yaml");
        RulesetConfig current = RulesetLoader.loadClasspath("rulesets/etsy-2026-12.yaml");

        assertThat(old.rule("banned_words").enabled()).isTrue();
        assertThat(current.rule("banned_words").enabled()).isFalse();
        assertThat(current.rule("banned_words").stringListParam("words")).isEqualTo(old.rule("banned_words").stringListParam("words"));
        for (String id : old.rules().keySet()) {
            if (id.equals("banned_words")) {
                continue;
            }
            assertThat(current.rule(id)).as("rule %s is unchanged", id).isEqualTo(old.rule(id));
        }
    }
}
