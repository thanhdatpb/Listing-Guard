package com.thanhdatpb.listingguard.api.console.editor;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.plan.Usage;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleLimit;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.ruleengine.config.Source;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The limits the editor shows are the limits of the active ruleset, as the rules declare them; the numbers are read from the
 * ruleset here, not repeated. That the page and {@code GET /api/v1/rulesets/{marketplace}/limits} agree is held by
 * {@code RulesetLimitsConsoleParityIntegrationTest}.
 */
class EditorLimitsTest {

    private static final RulesetConfig ETSY = RulesetLoader.loadClasspath("rulesets/etsy-2026-11.yaml");
    private static final RulesetConfig AMAZON = RulesetLoader.loadClasspath("rulesets/amazon-2026-12.yaml");

    private final RuleEngine engine = new RuleEngine(RuleRegistry.withDefaults());

    private EditorLimits limits(RulesetConfig config, String productType) {
        return EditorLimits.of(engine.limits(config, productType));
    }

    @Test
    void etsyLimitsAreTheRulesetValues() {
        EditorLimits limits = limits(ETSY, "");

        assertThat(limits.titleMax()).isEqualTo(ETSY.rule("title_length").intParam("max_length"));
        assertThat(limits.tagLengthMax()).isEqualTo(ETSY.rule("tag_length").intParam("max_length"));
        assertThat(limits.tagCountMax()).isEqualTo(ETSY.rule("tag_count").intParam("max_count"));
        assertThat(limits.bulletCountMax()).isNull();
        assertThat(limits.bulletLengthMax()).isNull();
        assertThat(limits.searchTermsMaxBytes()).isNull();
    }

    @Test
    void amazonLimitsAreTheRulesetValuesAndASwitchedOffRuleHasNoCounter() {
        EditorLimits limits = limits(AMAZON, "");

        assertThat(limits.titleMax()).isEqualTo(AMAZON.rule("title_length").intParam("max_length"));
        assertThat(limits.bulletCountMax()).isEqualTo(AMAZON.rule("bullet_count").intParam("max_count"));
        assertThat(limits.searchTermsMaxBytes()).isEqualTo(AMAZON.rule("search_terms_length").intParam("max_bytes"));
        // bullet_length is enabled: false in amazon-2026-12, so no counter shows an unverified number
        assertThat(AMAZON.rule("bullet_length").enabled()).isFalse();
        assertThat(limits.bulletLengthMax()).isNull();
        assertThat(limits.tagLengthMax()).isNull();
        assertThat(limits.tagCountMax()).isNull();
    }

    @Test
    void aProductTypeOverrideChangesTheAmazonLimitsExactlyAsItChangesTheCheck() {
        Source source = new Source(SourceStatus.UNVERIFIED, List.of(), "synthetic override for a test");
        RuleSettings override = new RuleSettings("title_length", Severity.WARNING, source, Map.of("max_length", 80));
        RuleSettings base = AMAZON.rule("title_length");
        RuleSettings withOverride = new RuleSettings(base.ruleId(), base.severity(), base.source(), base.params(),
                base.enabled(), Map.of("LUGGAGE", override));
        Map<String, RuleSettings> rules = new HashMap<>(AMAZON.rules());
        rules.put("title_length", withOverride);
        RulesetConfig config = new RulesetConfig(AMAZON.packId(), AMAZON.marketplace(), AMAZON.version(), AMAZON.checkedAt(),
                AMAZON.contentHash(), rules);

        assertThat(limits(config, "luggage").titleMax()).isEqualTo(80);
        assertThat(limits(config, " LUGGAGE ").titleMax()).isEqualTo(80);
        assertThat(limits(config, "SHOES").titleMax()).isEqualTo(base.intParam("max_length"));
        assertThat(limits(config, "").titleMax()).isEqualTo(base.intParam("max_length"));
    }

    @Test
    void aLimitOfAnotherFieldOrAnotherKindIsNotTakenForTheCounter() {
        List<RuleLimit> only = List.of(
                new RuleLimit("description", "x", Limit.Kind.MAX_LENGTH, 1, Limit.Unit.CODE_POINTS, Severity.ERROR,
                        SourceStatus.VERIFIED, true),
                new RuleLimit("title", "y", Limit.Kind.MAX_OCCURRENCES, 2, Limit.Unit.OCCURRENCES, Severity.ERROR,
                        SourceStatus.VERIFIED, true));

        assertThat(EditorLimits.of(only)).isEqualTo(new EditorLimits(null, null, null, null, null, null));
    }

    @Test
    void whenSeveralEnabledRulesLimitTheSameThingTheStrictestWinsAndASwitchedOffOneIsIgnored() {
        List<RuleLimit> limits = List.of(
                new RuleLimit("title", "a", Limit.Kind.MAX_LENGTH, 100, Limit.Unit.CODE_POINTS, Severity.WARNING,
                        SourceStatus.SECONDARY, true),
                new RuleLimit("title", "b", Limit.Kind.MAX_LENGTH, 60, Limit.Unit.CODE_POINTS, Severity.WARNING,
                        SourceStatus.SECONDARY, true),
                new RuleLimit("title", "c", Limit.Kind.MAX_LENGTH, 10, Limit.Unit.CODE_POINTS, Severity.WARNING,
                        SourceStatus.UNVERIFIED, false));

        assertThat(EditorLimits.of(limits).titleMax()).isEqualTo(60);
    }

    @Test
    void theCostIsOneUnitWithoutTheTrademarkPackAndTwoWithIt() {
        Instant reset = Instant.parse("2026-12-01T00:00:00Z");
        LocalDate period = LocalDate.of(2026, 11, 1);

        EditorCost free = EditorCost.of(new Usage(true, "FREE", 20, 3, 17L, period, reset, List.of("listing-rules")));
        EditorCost seller = EditorCost.of(new Usage(true, "SELLER", 1000, 3, 997L, period, reset,
                List.of("listing-rules", "trademark-risk")));
        EditorCost unlimited = EditorCost.of(new Usage(true, "PRO", null, 3, null, period, reset,
                List.of("listing-rules", "trademark-risk")));
        EditorCost off = EditorCost.of(new Usage(false, "FREE", null, 0, null, period, reset,
                List.of("listing-rules", "trademark-risk")));

        assertThat(free.units()).isEqualTo(1);
        assertThat(free.trademarkIncluded()).isFalse();
        assertThat(free.resetDate()).isEqualTo("2026-12-01");
        assertThat(seller.units()).isEqualTo(2);
        assertThat(seller.trademarkIncluded()).isTrue();
        assertThat(seller.remaining()).isEqualTo(997L);
        assertThat(unlimited.monthlyQuota()).isNull();
        assertThat(off.enforced()).isFalse();
    }
}
