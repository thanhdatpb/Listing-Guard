package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.limit;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleLimit;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The limits the Amazon rules declare are the numbers of the ruleset for the product type, in the unit the rule counts, and
 * they cannot differ from what the check enforces. The real ruleset has no product type override, so overrides are tested with
 * {@code rulesets/amazon-test-categories.yaml} (made-up product types TESTBAG and TESTMUG).
 */
class AmazonLimitsTest {

    private static final RulesetConfig TEST = RulesetLoader.loadClasspath("rulesets/amazon-test-categories.yaml");

    /** One code point, two UTF-16 units. */
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    private final RuleEngine engine = new RuleEngine(RuleRegistry.withDefaults());

    private RuleLimit limitOf(RulesetConfig config, String category, String ruleId) {
        List<RuleLimit> found = engine.limits(config, category).stream().filter(l -> l.ruleId().equals(ruleId)).toList();
        assertThat(found).as("limits of " + ruleId + " for " + category).hasSize(1);
        return found.get(0);
    }

    private List<Finding> run(RulesetConfig config, Listing listing) {
        return engine.run(listing, config);
    }

    @Test
    void theRealRulesetLimitsAreItsNumbersAndASwitchedOffRuleIsListedAsDisabled() {
        assertThat(limitOf(CONFIG, null, "title_length")).isEqualTo(new RuleLimit("title", "title_length", Limit.Kind.MAX_LENGTH,
                limit("title_length", "max_length"), Limit.Unit.CODE_POINTS, CONFIG.rule("title_length").severity(),
                CONFIG.rule("title_length").source().status(), true));
        assertThat(limitOf(CONFIG, null, "bullet_count")).extracting(RuleLimit::field, RuleLimit::kind, RuleLimit::value,
                        RuleLimit::unit)
                .containsExactly("bullet_points", Limit.Kind.MAX_COUNT, limit("bullet_count", "max_count"), Limit.Unit.ITEMS);
        assertThat(limitOf(CONFIG, null, "title_repeated_words")).extracting(RuleLimit::field, RuleLimit::kind, RuleLimit::unit)
                .containsExactly("title", Limit.Kind.MAX_OCCURRENCES, Limit.Unit.OCCURRENCES);

        RuleLimit bulletLength = limitOf(CONFIG, null, "bullet_length");
        assertThat(CONFIG.rule("bullet_length").enabled()).as("bullet_length is switched off in the ruleset").isFalse();
        assertThat(bulletLength.enabled()).isFalse();
        assertThat(bulletLength.value()).isEqualTo(limit("bullet_length", "max_length"));
        assertThat(bulletLength.sourceStatus()).isEqualTo(CONFIG.rule("bullet_length").source().status());
    }

    @Test
    void searchTermsAreCountedInUtf8BytesNotInCharacters() {
        RuleLimit terms = limitOf(CONFIG, null, "search_terms_length");
        int max = limit("search_terms_length", "max_bytes");
        // 'é' is two bytes: this has exactly max bytes but fewer than max characters
        String atLimit = "é".repeat(max / 2) + "a".repeat(max % 2);

        assertThat(terms.field()).isEqualTo("search_terms");
        assertThat(terms.kind()).isEqualTo(Limit.Kind.MAX_BYTES);
        assertThat(terms.unit()).isEqualTo(Limit.Unit.UTF8_BYTES);
        assertThat(terms.value()).isEqualTo(max);
        assertThat(atLimit.getBytes(StandardCharsets.UTF_8)).hasSize(max);
        assertThat(atLimit.length()).isLessThan(max);
        assertThat(run(CONFIG, AmazonTestData.withSearchTerms(atLimit))).extracting(Finding::ruleId)
                .doesNotContain("search_terms_length");
        assertThat(run(CONFIG, AmazonTestData.withSearchTerms(atLimit + "a"))).extracting(Finding::ruleId)
                .contains("search_terms_length");
    }

    @Test
    void theTitleIsCountedInCodePointsNotInUtf16Units() {
        RuleLimit title = limitOf(CONFIG, null, "title_length");
        String atLimit = EMOJI.repeat(title.value());

        assertThat(title.unit()).isEqualTo(Limit.Unit.CODE_POINTS);
        assertThat(atLimit.length()).isGreaterThan(title.value());
        assertThat(run(CONFIG, AmazonTestData.withTitle(atLimit))).extracting(Finding::ruleId).doesNotContain("title_length");
        assertThat(run(CONFIG, AmazonTestData.withTitle(atLimit + "a"))).extracting(Finding::ruleId).contains("title_length");
    }

    @Test
    void aProductTypeOverrideGivesTheOverrideValueSeverityAndSourceAndOtherTypesGetTheDefaults() {
        RuleLimit base = limitOf(TEST, null, "title_length");
        RuleLimit bag = limitOf(TEST, "TESTBAG", "title_length");
        RuleLimit mug = limitOf(TEST, " testmug ", "title_length");

        assertThat(base.value()).isEqualTo(TEST.rule("title_length").intParam("max_length"));
        assertThat(bag.value()).isEqualTo(TEST.rule("title_length", "TESTBAG").intParam("max_length")).isNotEqualTo(base.value());
        assertThat(bag.severity()).isEqualTo(Severity.ERROR);
        assertThat(bag.sourceStatus()).isEqualTo(SourceStatus.VERIFIED);
        assertThat(mug.value()).isEqualTo(TEST.rule("title_length", "TESTMUG").intParam("max_length"))
                .isNotEqualTo(base.value());
        assertThat(mug.severity()).isEqualTo(Severity.WARNING);
        assertThat(mug.sourceStatus()).isEqualTo(SourceStatus.SECONDARY);
        assertThat(limitOf(TEST, "SHOES", "title_length")).isEqualTo(base);
        assertThat(limitOf(TEST, "", "title_length").value()).as("a blank product type is no override").isEqualTo(base.value());
    }

    @Test
    void anOverrideCanSwitchARuleOffOrOnForOneProductType() {
        assertThat(limitOf(TEST, null, "bullet_count").enabled()).isTrue();
        assertThat(limitOf(TEST, "TESTBAG", "bullet_count").enabled()).isTrue();
        assertThat(limitOf(TEST, "TESTMUG", "bullet_count").enabled()).isFalse();

        assertThat(limitOf(TEST, null, "bullet_length").enabled()).isFalse();
        RuleLimit mug = limitOf(TEST, "TESTMUG", "bullet_length");
        assertThat(mug.enabled()).isTrue();
        assertThat(mug.value()).isEqualTo(TEST.rule("bullet_length", "TESTMUG").intParam("max_length"));
        assertThat(mug.unit()).isEqualTo(Limit.Unit.CODE_POINTS);
    }

    @Test
    void eachBulletIsCountedInCodePointsUnderTheOverride() {
        RuleLimit mug = limitOf(TEST, "TESTMUG", "bullet_length");
        Listing atLimit = AmazonTestData.listing("Mug", "Roadster", List.of(EMOJI.repeat(mug.value())), "ok", "mug", "TESTMUG");
        Listing over = AmazonTestData.listing("Mug", "Roadster", List.of(EMOJI.repeat(mug.value()) + "a"), "ok", "mug", "TESTMUG");

        assertThat(run(TEST, atLimit)).extracting(Finding::ruleId).doesNotContain("bullet_length");
        assertThat(run(TEST, over)).extracting(Finding::ruleId).contains("bullet_length");
    }

    /** The declared title limit and severity are those the check applies, for every product type of the test ruleset. */
    @Test
    void theDeclaredTitleLimitIsTheOneTheCheckEnforcesForEveryProductType() {
        for (String category : Arrays.asList(null, "TESTBAG", "TESTMUG", "SHOES")) {
            RuleLimit title = limitOf(TEST, category, "title_length");
            String atLimit = "a".repeat(title.value());

            assertThat(run(TEST, AmazonTestData.listing(atLimit, "Roadster", List.of(), "ok", "mug", category)))
                    .as("at the limit, product type " + category).extracting(Finding::ruleId).doesNotContain("title_length");
            List<Finding> over = run(TEST, AmazonTestData.listing(atLimit + "b", "Roadster", List.of(), "ok", "mug", category))
                    .stream().filter(f -> f.ruleId().equals("title_length")).toList();
            assertThat(over).as("over the limit, product type " + category).hasSize(1);
            assertThat(over.get(0).severity()).isEqualTo(title.severity());
        }
    }
}
