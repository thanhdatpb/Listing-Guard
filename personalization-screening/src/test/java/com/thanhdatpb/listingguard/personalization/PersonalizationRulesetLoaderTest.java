package com.thanhdatpb.listingguard.personalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRulesetException;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRulesetLoader;
import com.thanhdatpb.listingguard.personalization.config.RuleIds;
import com.thanhdatpb.listingguard.personalization.config.SourceStatus;
import com.thanhdatpb.listingguard.personalization.config.UnknownPrintProfileException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonalizationRulesetLoaderTest {

    /**
     * The SHA-256 of rulesets/personalization-screening-2026-11.yaml (LF line endings). A published version is immutable
     * (ADR-002): a change to the file is a new file with a new version, never an edit that updates this constant.
     */
    private static final String PINNED_HASH = "079c0108533c9a70f55cce8811581251bab0bd33dadceefbe2f3a745eec11215";

    @Test
    void theShippedRulesetIsPinned() {
        PersonalizationRuleset ruleset = Fixtures.ruleset();

        assertThat(ruleset.packId()).isEqualTo("personalization-screening");
        assertThat(ruleset.version()).isEqualTo("personalization-screening-2026-11");
        assertThat(ruleset.contentHash()).isEqualTo(PINNED_HASH);
    }

    @Test
    void theHashDoesNotDependOnLineEndingsOrABom() {
        String text = Fixtures.rulesetText();

        assertThat(PersonalizationRulesetLoader.parse(text.replace("\n", "\r\n")).contentHash()).isEqualTo(PINNED_HASH);
        assertThat(PersonalizationRulesetLoader.parse("\uFEFF" + text).contentHash()).isEqualTo(PINNED_HASH);
    }

    @Test
    void theShippedRulesetHasNothingButUnverifiedSourcesAndNoError() {
        PersonalizationRuleset ruleset = Fixtures.ruleset();

        List<SourceStatus> statuses = new ArrayList<>();
        ruleset.profiles().values().forEach(profile -> statuses.add(profile.source().status()));
        ruleset.rules().forEach(rule -> statuses.add(rule.source().status()));
        statuses.add(ruleset.trademark().source().status());
        assertThat(statuses).isNotEmpty().containsOnly(SourceStatus.UNVERIFIED);
        ruleset.rules().forEach(rule -> rule.kinds().values()
                .forEach(kind -> assertThat(kind.severity()).isNotEqualTo(Severity.ERROR)));
    }

    @Test
    void theDefaultProfileIsDeclaredAndAnUnknownOneIsRefusedWithTheKnownCodes() {
        PersonalizationRuleset ruleset = Fixtures.ruleset();

        assertThat(ruleset.profile(null).code()).isEqualTo(ruleset.defaultProfile());
        assertThat(ruleset.profile("  ").code()).isEqualTo(ruleset.defaultProfile());
        assertThatThrownBy(() -> ruleset.profile("no_such_profile"))
                .isInstanceOfSatisfying(UnknownPrintProfileException.class, e -> {
                    assertThat(e.code()).isEqualTo("no_such_profile");
                    assertThat(e.known()).first().isEqualTo(ruleset.defaultProfile());
                    assertThat(e.known()).containsExactlyInAnyOrderElementsOf(ruleset.profiles().keySet());
                });
    }

    @Test
    void everyMessageAvoidsTheWordingOfApproval() {
        PersonalizationRuleset ruleset = Fixtures.ruleset();
        List<String> texts = new ArrayList<>();
        texts.add(ruleset.disclaimer());
        ruleset.rules().forEach(rule -> rule.kinds().values().forEach(kind -> {
            texts.add(kind.message());
            texts.add(kind.suggestion());
        }));
        ruleset.trademark().rules().values().forEach(template -> {
            texts.add(template.message());
            texts.add(template.suggestion());
        });
        texts.add(ruleset.trademark().colorPhraseNote());

        for (String text : texts) {
            assertThat(text.toLowerCase()).doesNotContain("an toàn", "hợp lệ", "được phép", "đã được xác nhận", "tuân thủ",
                    "safe", "allowed", "permitted", "compliant", "approved");
        }
    }

    @Test
    void aStatusOtherThanUnverifiedIsRefused() {
        String first = "      source:\n        status: unverified\n        note: Placeholder with invented";
        assertThatThrownBy(() -> Fixtures.variant(first, first.replace("unverified", "secondary")))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("must be 'unverified'");
        assertThatThrownBy(() -> Fixtures.variant(first, first.replace("unverified", "verified")))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("must be 'unverified'");
    }

    @Test
    void anErrorSeverityIsRefused() {
        assertThatThrownBy(() -> Fixtures.variant("severity: WARNING", "severity: ERROR"))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("cannot be ERROR");
    }

    @Test
    void anUnknownPlaceholderIsRefused() {
        assertThatThrownBy(() -> Fixtures.variant("{chars}", "{nope}"))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("unknown placeholder");
        assertThatThrownBy(() -> Fixtures.variant("{mark}", "{chars}"))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("unknown placeholder");
    }

    @Test
    void aClassOrProfileThatIsReferredToButNotDeclaredIsRefused() {
        assertThatThrownBy(() -> Fixtures.variant("        - basic_punctuation\n", "        - not_a_class\n"))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("not_a_class");
        assertThatThrownBy(() -> Fixtures.variant("default_profile: latin_basic", "default_profile: missing"))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("default_profile");
    }

    @Test
    void aBadRangeOrAStrayKeyOrAMissingRuleIsRefused() {
        assertThatThrownBy(() -> Fixtures.variant("U+0041-U+005A", "U+005A-U+0041"))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("ends before it starts");
        assertThatThrownBy(() -> Fixtures.variant("U+0041-U+005A", "A-Z"))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("U+0041-U+005A");
        assertThatThrownBy(() -> Fixtures.variant("evidence:\n", "stray: 1\nevidence:\n"))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("Unknown key 'stray'");
        assertThatThrownBy(() -> Fixtures.variant("  too_long:\n    source:", "  too_long_x:\n    source:"))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("rules must declare exactly");
    }

    @Test
    void aMissingKindAndADuplicateKeyAreRefused() {
        assertThatThrownBy(() -> Fixtures.variant("      repeated_space:", "      repeated_spaces:"))
                .isInstanceOf(PersonalizationRulesetException.class).hasMessageContaining("kinds must be exactly");
        assertThatThrownBy(() -> Fixtures.variant("checked_at: 2026-11-06", "checked_at: 2026-11-06\nchecked_at: 2026-11-07"))
                .isInstanceOf(PersonalizationRulesetException.class);
    }

    @Test
    void everyRuleAndTrademarkRuleOfTheCodeIsInTheFile() {
        PersonalizationRuleset ruleset = Fixtures.ruleset();

        assertThat(ruleset.rules().stream().map(PersonalizationRuleset.RuleSpec::id).toList())
                .containsExactlyElementsOf(RuleIds.RULES);
        assertThat(ruleset.trademark().rules().keySet()).containsExactlyInAnyOrderElementsOf(RuleIds.TRADEMARK_RULES);
    }
}
