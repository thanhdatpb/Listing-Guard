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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link RuleEngine#limits} is generic: it knows no rule by name. It asks each registered rule for its {@link Limit}s and rates
 * them with the settings of the rule for the category, so any ruleset and any new rule is reflected without a change here.
 */
class RuleEngineLimitsTest {

    /** A rule that declares one limit whose value is the {@code n} setting, so the test sees which settings it was given. */
    private static Rule limited(String id, String field) {
        return new Rule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Finding> evaluate(Listing listing, RulesetConfig config) {
                return List.of();
            }

            @Override
            public List<Limit> limits(RuleSettings settings) {
                return List.of(Limit.maxLength(field, ((Number) settings.params().get("n")).intValue()));
            }
        };
    }

    private static Rule silent(String id) {
        return new Rule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Finding> evaluate(Listing listing, RulesetConfig config) {
                return List.of();
            }
        };
    }

    private static RuleSettings settings(String id, Severity severity, SourceStatus status, int n, boolean enabled,
                                         Map<String, RuleSettings> overrides) {
        return new RuleSettings(id, severity, new Source(status, List.of(), null), Map.of("n", n), enabled, overrides);
    }

    private static RulesetConfig config(RuleSettings... rules) {
        Map<String, RuleSettings> byId = new LinkedHashMap<>();
        for (RuleSettings rule : rules) {
            byId.put(rule.ruleId(), rule);
        }
        return new RulesetConfig("TEST", "test-1", LocalDate.of(2026, 11, 6), "hash", byId);
    }

    @Test
    void limitsAreInRegistrationOrderAndAreRatedBySettingsOfTheirOwnRule() {
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("TEST", limited("second_in_yaml", "tags"))
                .register("TEST", limited("first_in_yaml", "title")));
        RulesetConfig config = config(
                settings("first_in_yaml", Severity.ERROR, SourceStatus.VERIFIED, 7, true, Map.of()),
                settings("second_in_yaml", Severity.WARNING, SourceStatus.UNVERIFIED, 3, true, Map.of()));

        assertThat(engine.limits(config, null)).containsExactly(
                new RuleLimit("tags", "second_in_yaml", Limit.Kind.MAX_LENGTH, 3, Limit.Unit.CODE_POINTS, Severity.WARNING,
                        SourceStatus.UNVERIFIED, true),
                new RuleLimit("title", "first_in_yaml", Limit.Kind.MAX_LENGTH, 7, Limit.Unit.CODE_POINTS, Severity.ERROR,
                        SourceStatus.VERIFIED, true));
    }

    @Test
    void aRuleThatDeclaresNothingContributesNothing() {
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("TEST", silent("no_number"))
                .register("TEST", limited("with_number", "title")));
        RulesetConfig config = config(settings("no_number", Severity.ERROR, SourceStatus.VERIFIED, 1, true, Map.of()),
                settings("with_number", Severity.ERROR, SourceStatus.VERIFIED, 2, true, Map.of()));

        assertThat(engine.limits(config, null)).extracting(RuleLimit::ruleId).containsExactly("with_number");
    }

    @Test
    void aSwitchedOffRuleIsListedWithEnabledFalse() {
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("TEST", limited("off", "title")));
        RulesetConfig config = config(settings("off", Severity.WARNING, SourceStatus.UNVERIFIED, 9, false, Map.of()));

        assertThat(engine.limits(config, null)).singleElement().satisfies(limit -> {
            assertThat(limit.enabled()).isFalse();
            assertThat(limit.value()).isEqualTo(9);
        });
    }

    @Test
    void theRuleIsGivenTheSettingsOfTheCategoryAndTheLimitIsRatedByThem() {
        RuleSettings override = settings("r", Severity.ERROR, SourceStatus.VERIFIED, 5, true, Map.of());
        RuleSettings off = settings("r", Severity.WARNING, SourceStatus.SECONDARY, 8, false, Map.of());
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("TEST", limited("r", "title")));
        RulesetConfig config = config(settings("r", Severity.WARNING, SourceStatus.UNVERIFIED, 20, true,
                Map.of("BAG", override, "MUG", off)));

        assertThat(engine.limits(config, null)).singleElement().extracting(RuleLimit::value, RuleLimit::severity,
                RuleLimit::sourceStatus, RuleLimit::enabled).containsExactly(20, Severity.WARNING, SourceStatus.UNVERIFIED, true);
        assertThat(engine.limits(config, " bag ")).singleElement().extracting(RuleLimit::value, RuleLimit::severity,
                RuleLimit::sourceStatus, RuleLimit::enabled).containsExactly(5, Severity.ERROR, SourceStatus.VERIFIED, true);
        assertThat(engine.limits(config, "mug")).singleElement().extracting(RuleLimit::value, RuleLimit::enabled)
                .containsExactly(8, false);
        assertThat(engine.limits(config, "OTHER")).singleElement().extracting(RuleLimit::value).isEqualTo(20);
    }

    @Test
    void theResultCannotBeChangedByTheCaller() {
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("TEST", limited("r", "title")));
        List<RuleLimit> limits = engine.limits(config(settings("r", Severity.ERROR, SourceStatus.VERIFIED, 1, true, Map.of())), null);

        assertThatThrownBy(() -> limits.add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ArrayList<>(limits)).hasSize(1);
    }

    @Test
    void aRulesetWhoseMarketplaceHasNoRegisteredRulesIsAnError() {
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("OTHER", limited("r", "title")));

        assertThatThrownBy(() -> engine.limits(config(settings("r", Severity.ERROR, SourceStatus.VERIFIED, 1, true, Map.of())), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRegisteredRuleWithoutConfigurationIsAnError() {
        RuleEngine engine = new RuleEngine(new RuleRegistry().register("TEST", limited("missing", "title")));

        assertThatThrownBy(() -> engine.limits(config(), null)).isInstanceOf(RulesetException.class).hasMessageContaining("missing");
    }
}
