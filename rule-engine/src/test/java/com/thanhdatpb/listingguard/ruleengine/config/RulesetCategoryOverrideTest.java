package com.thanhdatpb.listingguard.ruleengine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Category overrides and {@code enabled} in the ruleset loader: how they merge, and what fails at load time. */
class RulesetCategoryOverrideTest {

    private static final String BASE = """
            marketplace: amazon
            version: v1
            checked_at: 2026-10-28
            rules:
              r1:
                severity: warning
                limit: 5
                words: [a, b]
                source:
                  status: unverified
                categories:
                  bag:
                    limit: 3
                    source:
                      status: verified
                      urls: [https://example.com/bag]
            """;

    private static RulesetConfig load() {
        return RulesetLoader.parse(BASE);
    }

    private static void assertFails(String yaml, String... messageParts) {
        assertThatThrownBy(() -> RulesetLoader.parse(yaml)).isInstanceOf(RulesetException.class).hasMessageContainingAll(messageParts);
    }

    // ---- merging ------------------------------------------------------------------------------------------

    @Test
    void anOverrideReplacesOnlyItsKeysAndKeepsTheRestOfTheDefaults() {
        RuleSettings defaults = load().rule("r1");
        RuleSettings bag = defaults.forCategory("BAG");

        assertThat(defaults.intParam("limit")).isEqualTo(5);
        assertThat(bag.intParam("limit")).isEqualTo(3);
        assertThat(bag.stringListParam("words")).containsExactly("a", "b");
        assertThat(bag.severity()).isEqualTo(Severity.WARNING);
        assertThat(bag.enabled()).isTrue();
        assertThat(bag.ruleId()).isEqualTo("r1");
        assertThat(bag.categoryOverrides()).isEmpty();
    }

    @Test
    void anOverrideCarriesItsOwnSource() {
        RuleSettings defaults = load().rule("r1");
        RuleSettings bag = defaults.forCategory("bag");

        assertThat(defaults.source().status()).isEqualTo(SourceStatus.UNVERIFIED);
        assertThat(bag.source().status()).isEqualTo(SourceStatus.VERIFIED);
        assertThat(bag.source().urls()).containsExactly("https://example.com/bag");
    }

    @Test
    void lookupIgnoresCaseAndSurroundingBlanksAndFallsBackToTheDefaults() {
        RuleSettings defaults = load().rule("r1");

        assertThat(defaults.forCategory(" Bag ").intParam("limit")).isEqualTo(3);
        assertThat(defaults.forCategory("bAg").intParam("limit")).isEqualTo(3);
        assertThat(defaults.forCategory("OTHER")).isSameAs(defaults);
        assertThat(defaults.forCategory(null)).isSameAs(defaults);
        assertThat(defaults.forCategory("")).isSameAs(defaults);
    }

    @Test
    void anOverrideCanChangeTheSeverity() {
        RulesetConfig config = RulesetLoader.parse(BASE.replace("    limit: 3\n", "    limit: 3\n        severity: error\n"));

        assertThat(config.rule("r1").severity()).isEqualTo(Severity.WARNING);
        assertThat(config.rule("r1", "BAG").severity()).isEqualTo(Severity.ERROR);
    }

    @Test
    void ruleAndConfigLookupsAgree() {
        RulesetConfig config = load();

        assertThat(config.rule("r1", "bag")).isEqualTo(config.rule("r1").forCategory("bag"));
        assertThat(config.rule("r1", null)).isSameAs(config.rule("r1"));
    }

    @Test
    void categoryNamesAreStoredInUpperCase() {
        assertThat(load().rule("r1").categoryOverrides()).containsOnlyKeys("BAG");
    }

    @Test
    void withOverridesListsTheDefaultsFirst() {
        List<RuleSettings> all = load().rule("r1").withOverrides();

        assertThat(all).hasSize(2);
        assertThat(all.get(0).intParam("limit")).isEqualTo(5);
        assertThat(all.get(1).intParam("limit")).isEqualTo(3);
    }

    // ---- enabled ------------------------------------------------------------------------------------------

    @Test
    void aRuleIsEnabledUnlessItSaysOtherwise() {
        assertThat(load().rule("r1").enabled()).isTrue();
    }

    @Test
    void enabledFalseSwitchesTheRuleOffButKeepsItsParameters() {
        RuleSettings settings = RulesetLoader.parse(BASE.replace("    limit: 5\n", "    limit: 5\n    enabled: false\n")).rule("r1");

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.intParam("limit")).isEqualTo(5);
        assertThat(settings.params()).doesNotContainKey("enabled");
    }

    @Test
    void anOverrideInheritsEnabledAndCanChangeIt() {
        String off = BASE.replace("    limit: 5\n", "    limit: 5\n    enabled: false\n");
        assertThat(RulesetLoader.parse(off).rule("r1", "bag").enabled()).isFalse();

        String enabledForBag = off.replace("    limit: 3\n", "    limit: 3\n        enabled: true\n");
        RuleSettings settings = RulesetLoader.parse(enabledForBag).rule("r1");
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.forCategory("bag").enabled()).isTrue();

        String disabledForBag = BASE.replace("    limit: 3\n", "    limit: 3\n        enabled: false\n");
        RuleSettings other = RulesetLoader.parse(disabledForBag).rule("r1");
        assertThat(other.enabled()).isTrue();
        assertThat(other.forCategory("bag").enabled()).isFalse();
    }

    @Test
    void enabledMustBeABoolean() {
        assertFails(BASE.replace("    limit: 5\n", "    limit: 5\n    enabled: maybe\n"), "r1", "enabled");
        assertFails(BASE.replace("    limit: 3\n", "    limit: 3\n        enabled: 1\n"), "r1", "bag", "enabled");
    }

    @Test
    void enabledAndCategoriesAreNotParameters() {
        assertThat(load().rule("r1").params()).containsOnlyKeys("limit", "words");
    }

    // ---- categories of the whole ruleset ---------------------------------------------------------------

    @Test
    void theRulesetKnowsEveryCategoryAnyRuleOverrides() {
        String twoRules = BASE + """
                  r2:
                    severity: info
                    other: 1
                    source:
                      status: unverified
                    categories:
                      mug:
                        other: 2
                        source:
                          status: unverified
                      Bag:
                        other: 3
                        source:
                          status: unverified
                """;
        RulesetConfig config = RulesetLoader.parse(twoRules);

        assertThat(config.categories()).containsExactly("BAG", "MUG");
        assertThat(config.coversCategory("mug")).isTrue();
        assertThat(config.coversCategory(" BAG ")).isTrue();
        assertThat(config.coversCategory("shoe")).isFalse();
        assertThat(config.coversCategory(null)).isFalse();
    }

    @Test
    void sourceUrlsIncludeTheOverrides() {
        assertThat(load().sourceUrls()).containsExactly("https://example.com/bag");
    }

    @Test
    void changingAnOverrideChangesTheContentHash() {
        assertThat(RulesetLoader.parse(BASE.replace("limit: 3", "limit: 4")).contentHash()).isNotEqualTo(load().contentHash());
    }

    @Test
    void aRulesetWithoutCategoriesCoversNothing() {
        RulesetConfig config = RulesetLoader.parse(BASE.substring(0, BASE.indexOf("    categories:")));

        assertThat(config.categories()).isEmpty();
        assertThat(config.rule("r1").categoryOverrides()).isEmpty();
        assertThat(config.rule("r1", "bag")).isSameAs(config.rule("r1"));
    }

    // ---- fail-fast ---------------------------------------------------------------------------------------

    @Test
    void anOverrideOfAKeyTheRuleDoesNotDefineFails() {
        assertFails(BASE.replace("    limit: 3\n", "    limit: 3\n        lmit: 9\n"), "r1", "bag", "lmit");
    }

    @Test
    void anOverrideWithoutASourceFails() {
        String noSource = BASE.substring(0, BASE.indexOf("        source:\n          status: verified"));

        assertFails(noSource, "r1", "bag", "source");
    }

    @Test
    void anOverrideMustMeetTheSameSourceRulesAsARule() {
        assertFails(BASE.replace("urls: [https://example.com/bag]", "urls: []"), "r1", "bag", "URL");
        assertFails(BASE.replace("status: verified", "status: certain"), "r1", "bag", "certain");
    }

    @Test
    void anOverrideWithAnInvalidSeverityFails() {
        assertFails(BASE.replace("    limit: 3\n", "    limit: 3\n        severity: fatal\n"), "r1", "bag", "fatal");
    }

    @Test
    void categoriesCannotBeNested() {
        assertFails(BASE.replace("    limit: 3\n", "    limit: 3\n        categories:\n          cup:\n            limit: 1\n"),
                "r1", "bag", "categories");
    }

    @Test
    void theSameCategoryTwiceFailsEvenWithDifferentCase() {
        String twice = BASE + """
                      BAG:
                        limit: 2
                        source:
                          status: unverified
                """;

        assertFails(twice, "r1", "twice");
    }

    @Test
    void categoriesMustBeAnonEmptyMappingOfMappings() {
        assertFails(BASE.substring(0, BASE.indexOf("    categories:")) + "    categories: {}\n", "r1", "categories");
        assertFails(BASE.substring(0, BASE.indexOf("    categories:")) + "    categories: [bag]\n", "r1", "categories");
        assertFails(BASE.substring(0, BASE.indexOf("    categories:")) + "    categories:\n      bag: 3\n", "r1", "bag", "mapping");
    }

    @Test
    void aBlankCategoryNameFails() {
        assertFails(BASE.replace("      bag:\n", "      \" \":\n"), "r1", "empty");
    }

    // ---- stringMapParam ---------------------------------------------------------------------------------

    @Test
    void aStringMapParamKeepsFileOrder() {
        RuleSettings settings = new RuleSettings("r", Severity.INFO, new Source(SourceStatus.UNVERIFIED, List.of(), null),
                Map.of("m", new java.util.LinkedHashMap<>(Map.of("one", "1"))));

        assertThat(settings.stringMapParam("m")).containsExactly(Map.entry("one", "1"));
    }

    @Test
    void aStringMapParamMustBeANonEmptyMapOfNonBlankStrings() {
        for (Object bad : List.of(Map.of(), Map.of("k", 3), Map.of("k", " "), "text", List.of("a"))) {
            RuleSettings settings = new RuleSettings("r", Severity.INFO, new Source(SourceStatus.UNVERIFIED, List.of(), null),
                    Map.of("m", bad));

            assertThatThrownBy(() -> settings.stringMapParam("m")).as(String.valueOf(bad)).isInstanceOf(RulesetException.class)
                    .hasMessageContaining("'m'");
        }
        RuleSettings missing = new RuleSettings("r", Severity.INFO, new Source(SourceStatus.UNVERIFIED, List.of(), null), Map.of());
        assertThatThrownBy(() -> missing.stringMapParam("m")).isInstanceOf(RulesetException.class).hasMessageContaining("m");
    }
}
