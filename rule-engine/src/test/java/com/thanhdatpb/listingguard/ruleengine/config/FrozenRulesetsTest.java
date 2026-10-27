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
    void etsy202611IsADifferentVersionWithDifferentContent() {
        RulesetConfig old = RulesetLoader.loadClasspath("rulesets/etsy-2026-10.yaml");
        RulesetConfig current = RulesetLoader.loadClasspath("rulesets/etsy-2026-11.yaml");

        assertThat(current.version()).isEqualTo("etsy-2026-11");
        assertThat(current.contentHash()).isNotEqualTo(old.contentHash());
        assertThat(current.rules().keySet()).containsAll(old.rules().keySet());
    }
}
