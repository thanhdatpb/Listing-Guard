package com.thanhdatpb.listingguard.ruleengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.Source;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private static final Listing LISTING = new Listing(null, null, List.of(), null, null, List.of(), List.of(), null, null, null);

    private static RuleSettings settings(String id, Severity severity) {
        return new RuleSettings(id, severity, new Source(SourceStatus.UNVERIFIED, List.of(), null), Map.of());
    }

    private static RulesetConfig config(RuleSettings... settings) {
        return config("listing-rules", settings);
    }

    private static RulesetConfig config(String packId, RuleSettings... settings) {
        Map<String, RuleSettings> rules = new LinkedHashMap<>();
        for (RuleSettings s : settings) {
            rules.put(s.ruleId(), s);
        }
        return new RulesetConfig(packId, "TEST", "test-1", LocalDate.of(2026, 10, 27), "hash", rules);
    }

    private static Rule rule(String id, boolean throwing) {
        return new Rule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Finding> evaluate(Listing listing, RulesetConfig config) {
                if (throwing) {
                    throw new IllegalStateException("boom");
                }
                return List.of(finding(config, "title", "message from " + id, "fix " + id));
            }
        };
    }

    @Test
    void runsAllRulesAndKeepsRegistrationOrder() {
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("TEST", rule("a", false)).register("TEST", rule("b", false)));

        List<Finding> findings = engine.run(LISTING, config(settings("a", Severity.ERROR), settings("b", Severity.WARNING)));

        assertThat(findings).extracting(Finding::ruleId).containsExactly("a", "b");
        assertThat(findings).extracting(Finding::rulesetVersion).containsOnly("test-1");
    }

    @Test
    void failingRuleIsSkippedAndOthersStillRun() {
        RuleEngine engine = new RuleEngine(new RuleRegistry()
                .register("TEST", rule("first", false))
                .register("TEST", rule("broken", true))
                .register("TEST", rule("last", false)));

        List<Finding> findings = engine.run(LISTING, config(
                settings("first", Severity.ERROR), settings("broken", Severity.ERROR), settings("last", Severity.ERROR)));

        assertThat(findings).extracting(Finding::ruleId).containsExactly("first", "last");
    }

    @Test
    void severityComesFromConfigNotFromTheRuleClass() {
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("TEST", rule("a", false)));

        assertThat(engine.run(LISTING, config(settings("a", Severity.ERROR))).get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(engine.run(LISTING, config(settings("a", Severity.INFO))).get(0).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void onlyRulesOfTheConfigMarketplaceRun() {
        RuleEngine engine = new RuleEngine(new RuleRegistry()
                .register("TEST", rule("mine", false))
                .register("OTHER", rule("theirs", false)));

        List<Finding> findings = engine.run(LISTING, config(settings("mine", Severity.ERROR)));

        assertThat(findings).extracting(Finding::ruleId).containsExactly("mine");
    }

    @Test
    void rulePacksAreSelectedIndependentlyForTheSameMarketplace() {
        RuleEngine engine = new RuleEngine(new RuleRegistry()
                .register("listing-rules", "TEST", rule("text", false))
                .register("image-rules", "TEST", rule("image", false)));

        List<Finding> findings = engine.run(LISTING, config("image-rules", settings("image", Severity.INFO)));

        assertThat(findings).extracting(Finding::ruleId).containsExactly("image");
    }

    @Test
    void unknownMarketplaceIsRejectedInsteadOfReturningNoFindings() {
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("OTHER", rule("x", false)));

        assertThatThrownBy(() -> engine.run(LISTING, config(settings("x", Severity.ERROR))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateRuleIdIsRejected() {
        RuleRegistry registry = new RuleRegistry().register("TEST", rule("a", false));

        assertThatThrownBy(() -> registry.register("TEST", rule("a", false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateFailsWhenARuleHasNoConfiguration() {
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("TEST", rule("a", false)));

        assertThatThrownBy(() -> engine.validate(config(settings("other", Severity.ERROR))))
                .isInstanceOf(RulesetException.class)
                .hasMessageContaining("'a'");
    }

    @Test
    void validateFailsWhenConfigNamesAnUnknownRule() {
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("TEST", rule("a", false)));

        assertThatThrownBy(() -> engine.validate(config(settings("a", Severity.ERROR), settings("typo", Severity.ERROR))))
                .isInstanceOf(RulesetException.class)
                .hasMessageContaining("typo");
    }
}
