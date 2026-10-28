package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Category overrides through the engine, with the test ruleset {@code rulesets/amazon-test-categories.yaml} (made-up
 * product types TESTBAG and TESTMUG). The real Amazon ruleset has no overrides.
 */
class AmazonCategoryOverridesTest {

    private static final String RESOURCE = "rulesets/amazon-test-categories.yaml";
    private static final RulesetConfig CONFIG = RulesetLoader.loadClasspath(RESOURCE);

    private final RuleEngine engine = new RuleEngine(RuleRegistry.withDefaults());

    private static Listing listingOf(String productType, String title, List<String> bullets) {
        return listing(title, "Roadster", bullets, "ok", "carry on", productType);
    }

    private List<Finding> run(Listing listing) {
        return engine.run(listing, CONFIG);
    }

    private static String resourceText() throws IOException {
        try (InputStream in = AmazonCategoryOverridesTest.class.getResourceAsStream("/" + RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theTestRulesetIsValidForTheRegisteredRules() {
        engine.validate(CONFIG);
        assertThat(CONFIG.categories()).containsExactly("TESTBAG", "TESTMUG");
    }

    // ---- thresholds and severity ----------------------------------------------------------------------

    @Test
    void aTitleOf70CharactersIsFineByDefaultButTooLongForTestbag() {
        String title = "a".repeat(70);

        assertThat(run(listingOf("LUGGAGE", title, List.of()))).extracting(Finding::ruleId).doesNotContain("title_length");

        List<Finding> bag = run(listingOf("TESTBAG", title, List.of()));
        assertThat(bag).extracting(Finding::ruleId).contains("title_length");
        Finding finding = bag.stream().filter(f -> f.ruleId().equals("title_length")).findFirst().orElseThrow();
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.message()).isEqualTo("Tiêu đề dài 70 ký tự, tối đa 60.");
    }

    @Test
    void theDefaultSeverityAppliesToOtherProductTypes() {
        List<Finding> findings = run(listingOf("LUGGAGE", "a".repeat(101), List.of()));

        assertThat(findings).filteredOn(f -> f.ruleId().equals("title_length")).extracting(Finding::severity)
                .containsExactly(Severity.WARNING);
        assertThat(findings.stream().filter(f -> f.ruleId().equals("title_length")).findFirst().orElseThrow().message())
                .contains("tối đa 100");
    }

    @Test
    void anOverrideThatKeepsTheSeverityOnlyChangesTheNumber() {
        assertThat(run(listingOf("TESTMUG", "a".repeat(80), List.of()))).extracting(Finding::ruleId).doesNotContain("title_length");

        List<Finding> findings = run(listingOf("TESTMUG", "a".repeat(81), List.of()));
        Finding finding = findings.stream().filter(f -> f.ruleId().equals("title_length")).findFirst().orElseThrow();
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.message()).contains("tối đa 80");
    }

    @Test
    void theProductTypeMatchesIgnoringCaseAndBlanks() {
        String title = "a".repeat(70);

        assertThat(run(listingOf("testbag", title, List.of()))).extracting(Finding::ruleId).contains("title_length");
        assertThat(run(listingOf("  TestBag ", title, List.of()))).extracting(Finding::ruleId).contains("title_length");
    }

    @Test
    void aListingWithoutAProductTypeGetsTheDefaults() {
        List<Finding> findings = run(listingOf(null, "a".repeat(70), List.of()));

        assertThat(findings).extracting(Finding::ruleId).doesNotContain("title_length");
    }

    // ---- fields -----------------------------------------------------------------------------------------

    @Test
    void anOverrideCanRequireMoreFields() {
        Listing missing = new Listing(null, null, List.of(), null, null, List.of(), List.of(), null, null, null, null, List.of(), null, "TESTBAG");

        assertThat(run(missing)).filteredOn(f -> f.ruleId().equals("required_fields")).extracting(Finding::field)
                .containsExactly("brand", "title");
        assertThat(run(new Listing(null, null, List.of(), null, null, List.of(), List.of(), null, null, null, null, List.of(), null, "LUGGAGE")))
                .filteredOn(f -> f.ruleId().equals("required_fields")).isEmpty();
    }

    // ---- enabled ------------------------------------------------------------------------------------------

    @Test
    void anOverrideCanSwitchARuleOffForOneProductType() {
        List<String> six = Collections.nCopies(6, "Bullet");

        assertThat(run(listingOf("LUGGAGE", "ok", six))).extracting(Finding::ruleId).contains("bullet_count");
        assertThat(run(listingOf("TESTMUG", "ok", six))).extracting(Finding::ruleId).doesNotContain("bullet_count");
        assertThat(run(listingOf("TESTBAG", "ok", six))).extracting(Finding::ruleId).contains("bullet_count");
    }

    @Test
    void anOverrideCanSwitchOnARuleThatIsOffByDefault() {
        List<String> bullets = List.of("short", "12345678901");

        assertThat(run(listingOf("LUGGAGE", "ok", bullets))).extracting(Finding::ruleId).doesNotContain("bullet_length");

        List<Finding> mug = run(listingOf("TESTMUG", "ok", bullets));
        assertThat(mug).filteredOn(f -> f.ruleId().equals("bullet_length")).extracting(Finding::field)
                .containsExactly("bullet_points[1]");
        assertThat(mug.stream().filter(f -> f.ruleId().equals("bullet_length")).findFirst().orElseThrow().message())
                .contains("dài 11 ký tự, tối đa 10");
    }

    // ---- coverage info -------------------------------------------------------------------------------------

    @Test
    void theCategoryInfoOnlyAppearsForProductTypesWithoutOverrides() {
        assertThat(run(listingOf("TESTBAG", "ok", List.of()))).extracting(Finding::ruleId).doesNotContain("category_not_covered");
        assertThat(run(listingOf("TESTMUG", "ok", List.of()))).extracting(Finding::ruleId).doesNotContain("category_not_covered");
        assertThat(run(listingOf("LUGGAGE", "ok", List.of()))).extracting(Finding::ruleId).contains("category_not_covered");
    }

    // ---- sources ------------------------------------------------------------------------------------------

    @Test
    void anOverrideKeepsItsOwnSourceNextToTheDefaultOne() {
        assertThat(CONFIG.rule("title_length").source().status()).isEqualTo(SourceStatus.SECONDARY);
        assertThat(CONFIG.rule("title_length", "TESTBAG").source().status()).isEqualTo(SourceStatus.VERIFIED);
        assertThat(CONFIG.rule("title_length", "TESTBAG").source().urls()).containsExactly("https://example.com/testbag-schema");
        assertThat(CONFIG.sourceUrls()).contains("https://example.com/testbag-schema", "https://example.com/testmug-style-guide");
    }

    // ---- fail-fast: overrides are validated by the rules at startup --------------------------------------------

    @Test
    void anOverrideWithAMalformedValueFailsTheEngineValidation() throws IOException {
        RulesetConfig broken = RulesetLoader.parse(resourceText().replace("max_length: 60", "max_length: lots"));

        assertThatThrownBy(() -> engine.validate(broken)).isInstanceOf(RulesetException.class)
                .hasMessageContaining("title_length").hasMessageContaining("max_length");
    }

    @Test
    void anOverrideWithAnUnknownRequiredFieldFailsTheEngineValidation() throws IOException {
        RulesetConfig broken = RulesetLoader.parse(resourceText().replace("          - title\n", "          - colour\n"));

        assertThatThrownBy(() -> engine.validate(broken)).isInstanceOf(RulesetException.class).hasMessageContaining("colour");
    }

    @Test
    void anOverrideOfAKeyTheRuleDoesNotHaveFailsAtLoadTime() throws IOException {
        String text = resourceText().replace("        max_length: 60\n", "        max_lenght: 60\n");

        assertThatThrownBy(() -> RulesetLoader.parse(text)).isInstanceOf(RulesetException.class)
                .hasMessageContaining("title_length").hasMessageContaining("TESTBAG").hasMessageContaining("max_lenght");
    }

    @Test
    void anOverrideWithoutASourceFailsAtLoadTime() throws IOException {
        String text = resourceText().replace("""
                      testmug:
                        max_length: 80
                        source:
                          status: secondary
                          urls:
                            - https://example.com/testmug-style-guide
                """, """
                      testmug:
                        max_length: 80
                """);

        assertThatThrownBy(() -> RulesetLoader.parse(text)).isInstanceOf(RulesetException.class)
                .hasMessageContaining("testmug").hasMessageContaining("source");
    }
}
