package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.valid;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.withCustomization;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.CustomizationSurface;
import com.thanhdatpb.listingguard.core.listing.CustomizationSurface.Dropdown;
import com.thanhdatpb.listingguard.core.listing.CustomizationSurface.ImageInput;
import com.thanhdatpb.listingguard.core.listing.CustomizationSurface.TextInput;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleLimit;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The Amazon Custom rules (the optional {@code customization} block). Every number is read from the ruleset: a value at the
 * limit passes and one more fails. All of them are WARNING except the free text reminder, which is INFO.
 */
class CustomizationRulesTest {

    private final RuleEngine engine = new RuleEngine(RuleRegistry.withDefaults());

    private static int max(String ruleId) {
        return CONFIG.rule(ruleId).intParam("max_count");
    }

    private static TextInput text(String label) {
        return new TextInput(label, 20, List.of("letters"));
    }

    private static List<TextInput> texts(int count) {
        return Collections.nCopies(count, text("Message"));
    }

    private static Dropdown dropdown(String label, String... options) {
        return new Dropdown(label, List.of(options));
    }

    private static Dropdown dropdownOfSize(String label, int size) {
        return new Dropdown(label, IntStream.range(0, size).mapToObj(i -> "Option " + i).toList());
    }

    private static CustomizationSurface surface(String name, List<TextInput> texts, List<Dropdown> dropdowns, int images) {
        return new CustomizationSurface(name, texts, dropdowns, Collections.nCopies(images, new ImageInput("Photo")));
    }

    private static CustomizationSurface textsOnly(List<TextInput> texts) {
        return surface("Front", texts, List.of(), 0);
    }

    private List<Finding> run(Listing listing, String ruleId) {
        return engine.run(listing, CONFIG).stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    @Test
    void aListingWithoutCustomizationHasNoCustomizationFinding() {
        assertThat(engine.run(valid(), CONFIG)).isEmpty();
        assertThat(engine.run(withCustomization(), CONFIG)).isEmpty();
    }

    @Test
    void aWellFormedCustomizationHasNoFinding() {
        Listing listing = withCustomization(
                surface("Front", List.of(text("Message")), List.of(dropdown("Font", "Serif", "Script")), 1),
                surface("Back", List.of(text("Short note")), List.of(dropdown("Color", "Red", "Blue")), 0));

        assertThat(engine.run(listing, CONFIG)).isEmpty();
    }

    @Nested
    class TextInputCount {

        @Test
        void exactlyTheLimitPassesAndOneMoreIsAWarningOverAllSurfaces() {
            int max = max("customization_text_input_count");
            Listing atLimit = withCustomization(textsOnly(texts(max - 1)), textsOnly(texts(1)));
            Listing over = withCustomization(textsOnly(texts(max)), textsOnly(texts(1)));

            assertThat(run(atLimit, "customization_text_input_count")).isEmpty();
            List<Finding> findings = run(over, "customization_text_input_count");
            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.field()).isEqualTo("customization.surfaces[].text_inputs");
            assertThat(finding.message()).contains((max + 1) + " ô nhập chữ").contains("tối đa " + max);
            assertThat(finding.suggestion()).contains("Bỏ bớt 1 ô nhập chữ");
            assertThat(finding.fix()).as("no rule proposes replacement words").isNull();
        }

        @Test
        void aSingleSurfaceOverTheLimitIsReportedToo() {
            int max = max("customization_text_input_count");

            assertThat(run(withCustomization(textsOnly(texts(max + 1))), "customization_text_input_count")).hasSize(1);
            assertThat(run(withCustomization(textsOnly(texts(max))), "customization_text_input_count")).isEmpty();
        }
    }

    @Nested
    class DropdownOptions {

        @Test
        void exactlyTheLimitPassesAndOneMoreOnASurfaceIsAWarning() {
            int max = max("customization_dropdown_options");

            assertThat(run(withCustomization(surface("Front", List.of(), List.of(dropdownOfSize("Size", max)), 0)),
                    "customization_dropdown_options")).isEmpty();
            List<Finding> findings = run(
                    withCustomization(surface("Front", List.of(), List.of(dropdownOfSize("Size", max + 1)), 0)),
                    "customization_dropdown_options");
            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.field()).isEqualTo("customization.surfaces[0].dropdowns");
            assertThat(finding.message()).contains("'Front'").contains((max + 1) + " lựa chọn").contains("tối đa " + max);
            assertThat(finding.suggestion()).contains("Bỏ bớt 1 lựa chọn");
        }

        @Test
        void theOptionsOfAllDropdownsOfOneSurfaceAreCountedTogether() {
            int max = max("customization_dropdown_options");
            Listing split = withCustomization(
                    surface("Front", List.of(), List.of(dropdownOfSize("A", max - 1), dropdownOfSize("B", 2)), 0));

            assertThat(run(split, "customization_dropdown_options")).hasSize(1);
        }

        @Test
        void theLimitIsPerSurfaceNotForTheWholeProduct() {
            int max = max("customization_dropdown_options");
            Listing two = withCustomization(
                    surface("Front", List.of(), List.of(dropdownOfSize("Size", max)), 0),
                    surface("Back", List.of(), List.of(dropdownOfSize("Size", max)), 0));

            assertThat(run(two, "customization_dropdown_options")).isEmpty();
        }

        @Test
        void eachSurfaceOverTheLimitIsReportedAtItsOwnPosition() {
            int max = max("customization_dropdown_options");
            Listing listing = withCustomization(
                    surface("Front", List.of(), List.of(dropdownOfSize("Size", max + 1)), 0),
                    surface("Back", List.of(), List.of(dropdownOfSize("Size", max)), 0),
                    surface(null, List.of(), List.of(dropdownOfSize("Size", max + 2)), 0));

            List<Finding> findings = run(listing, "customization_dropdown_options");

            assertThat(findings).extracting(Finding::field)
                    .containsExactly("customization.surfaces[0].dropdowns", "customization.surfaces[2].dropdowns");
            assertThat(findings.get(1).message()).contains("bề mặt in thứ 3");
        }

        @Test
        void aLabelRepeatedInTheSameDropdownIsAWarningAtTheRepeat() {
            Listing listing = withCustomization(
                    surface("Front", List.of(), List.of(dropdown("Font", "Serif", "Script", "Serif")), 0));

            List<Finding> findings = run(listing, "customization_dropdown_options");

            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.field()).isEqualTo("customization.surfaces[0].dropdowns[0].options[2]");
            assertThat(finding.value()).isEqualTo("Serif");
            assertThat(finding.message()).contains("'Serif'").contains("'Font'").contains("vị trí 1");
            assertThat(finding.fix()).isNull();
        }

        @Test
        void theSameLabelInTwoDropdownsIsNotARepeat() {
            Listing listing = withCustomization(surface("Front", List.of(),
                    List.of(dropdown("Font", "Serif", "Script"), dropdown("Style", "Serif", "Script")), 0));

            assertThat(run(listing, "customization_dropdown_options")).isEmpty();
        }

        @Test
        void onlyExactRepeatsCountWhileTheRulesetDoesNotIgnoreCase() {
            assertThat(CONFIG.rule("customization_dropdown_options").boolParam("case_insensitive")).isFalse();

            assertThat(run(withCustomization(surface("Front", List.of(), List.of(dropdown("Font", "Red", "red")), 0)),
                    "customization_dropdown_options")).isEmpty();
        }

        @Test
        void caseIsIgnoredWhenTheRulesetSaysSo() {
            RulesetConfig custom = RulesetLoader.parse("""
                    marketplace: AMAZON
                    version: t
                    checked_at: 2026-11-06
                    rules:
                      customization_dropdown_options:
                        severity: WARNING
                        max_count: 100
                        case_insensitive: true
                        source:
                          status: unverified
                    """);

            assertThat(new CustomizationDropdownOptionsRule().evaluate(
                    withCustomization(surface("Front", List.of(), List.of(dropdown("Font", "Red", "red")), 0)), custom))
                    .hasSize(1);
        }
    }

    @Nested
    class ImageInputCount {

        @Test
        void exactlyTheLimitPassesAndOneMoreOnASurfaceIsAWarning() {
            int max = max("customization_image_input_count");

            assertThat(run(withCustomization(surface("Front", List.of(), List.of(), max)), "customization_image_input_count"))
                    .isEmpty();
            List<Finding> findings = run(withCustomization(surface("Front", List.of(), List.of(), max + 1)),
                    "customization_image_input_count");
            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.field()).isEqualTo("customization.surfaces[0].image_inputs");
            assertThat(finding.message()).contains("'Front'").contains((max + 1) + " ô tải ảnh").contains("tối đa " + max);
        }

        @Test
        void theLimitIsPerSurface() {
            int max = max("customization_image_input_count");

            assertThat(run(withCustomization(surface("Front", List.of(), List.of(), max),
                    surface("Back", List.of(), List.of(), max)), "customization_image_input_count")).isEmpty();
            assertThat(run(withCustomization(surface("Front", List.of(), List.of(), max),
                    surface("Back", List.of(), List.of(), max + 1)), "customization_image_input_count"))
                    .extracting(Finding::field).containsExactly("customization.surfaces[1].image_inputs");
        }
    }

    @Nested
    class TextInputMaxChars {

        @Test
        void aTextInputWithoutMaxCharsIsAWarningAtItsPosition() {
            Listing listing = withCustomization(textsOnly(List.of(text("Message"), new TextInput("Name", null, List.of()))));

            List<Finding> findings = run(listing, "customization_text_input_max_chars");

            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.field()).isEqualTo("customization.surfaces[0].text_inputs[1].max_chars");
            assertThat(finding.message()).contains("'Name'").contains("'Front'").contains("tràn");
            assertThat(finding.suggestion()).contains("max_chars");
            assertThat(finding.fix()).isNull();
        }

        @Test
        void anInputWithoutALabelIsNamedByItsPosition() {
            List<Finding> findings = run(withCustomization(textsOnly(List.of(new TextInput(null, null, List.of())))),
                    "customization_text_input_max_chars");

            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).message()).contains("Ô nhập chữ thứ 1");
        }

        @Test
        void everyInputWithAMaxCharsPasses() {
            assertThat(run(withCustomization(textsOnly(texts(3))), "customization_text_input_max_chars")).isEmpty();
        }
    }

    @Nested
    class FreeTextSensitive {

        private final List<String> keywords = CONFIG.rule("customization_free_text_sensitive").stringListParam("keywords");

        @Test
        void aPlayerNameLabelIsAnInfoSuggestingADropdownOrCharacterLimits() {
            List<Finding> findings = run(withCustomization(textsOnly(List.of(text("Player Name")))),
                    "customization_free_text_sensitive");

            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
            assertThat(finding.field()).isEqualTo("customization.surfaces[0].text_inputs[0].label");
            assertThat(finding.value()).isEqualTo("Player Name");
            assertThat(finding.message()).contains("'Player Name'").contains("'player'").contains("'name'");
            assertThat(finding.suggestion()).contains("dropdown").contains("allowed_char_types")
                    .contains("không phải kết luận về nhãn hiệu");
            assertThat(finding.fix()).as("no rule proposes replacement words").isNull();
        }

        @Test
        void everyConfiguredKeywordIsFoundIgnoringCase() {
            assertThat(keywords).isNotEmpty();
            for (String keyword : keywords) {
                assertThat(run(withCustomization(textsOnly(List.of(text("Your " + keyword.toUpperCase() + " here")))),
                        "customization_free_text_sensitive")).as("keyword '%s'", keyword).hasSize(1);
            }
        }

        @Test
        void aKeywordIsAWholeWord() {
            assertThat(run(withCustomization(textsOnly(List.of(text("Teammate"), text("Numbered"), text("Playlist")))),
                    "customization_free_text_sensitive")).isEmpty();
        }

        @Test
        void onlyTheLabelOfATextInputIsRead() {
            Listing listing = withCustomization(new CustomizationSurface("Team player",
                    List.of(text("Message"), new TextInput(null, 20, List.of())),
                    List.of(dropdown("Team name", "Red", "Blue")), List.of(new ImageInput("Player photo"))));

            assertThat(run(listing, "customization_free_text_sensitive")).isEmpty();
        }

        @Test
        void oneFindingPerInputNoMatterHowManyKeywords() {
            assertThat(run(withCustomization(textsOnly(List.of(text("Team name and player number")))),
                    "customization_free_text_sensitive")).hasSize(1);
        }

        @Test
        void itCanNeverBeMoreThanInfo() {
            assertThatThrownBy(() -> new CustomizationFreeTextSensitiveRule().validate(config("WARNING", "[name]")))
                    .isInstanceOf(RulesetException.class).hasMessageContaining("INFO");
            assertThatThrownBy(() -> new CustomizationFreeTextSensitiveRule().validate(config("ERROR", "[name]")))
                    .isInstanceOf(RulesetException.class);
            assertThat(CONFIG.rule("customization_free_text_sensitive").severity()).isEqualTo(Severity.INFO);
            assertThat(CONFIG.rule("customization_free_text_sensitive").source().status()).isEqualTo(SourceStatus.UNVERIFIED);
        }

        @Test
        void theKeywordsComeFromTheRulesetAndAreValidated() {
            RulesetConfig custom = config("INFO", "[size]");

            assertThat(new CustomizationFreeTextSensitiveRule().evaluate(
                    withCustomization(textsOnly(List.of(text("Player name"), text("Size")))), custom))
                    .extracting(Finding::value).containsExactly("Size");
            assertThatThrownBy(() -> new CustomizationFreeTextSensitiveRule().validate(config("INFO", "[]")))
                    .isInstanceOf(RulesetException.class).hasMessageContaining("keywords");
            assertThatThrownBy(() -> new CustomizationFreeTextSensitiveRule().validate(config("INFO", "['  ']")))
                    .isInstanceOf(RulesetException.class);
        }

        private RulesetConfig config(String severity, String keywordsYaml) {
            return RulesetLoader.parse("""
                    marketplace: AMAZON
                    version: t
                    checked_at: 2026-11-06
                    rules:
                      customization_free_text_sensitive:
                        severity: %s
                        keywords: %s
                        source:
                          status: unverified
                    """.formatted(severity, keywordsYaml));
        }
    }

    @Nested
    class Limits {

        private RuleLimit limitOf(String ruleId) {
            List<RuleLimit> found = engine.limits(CONFIG, null).stream().filter(l -> l.ruleId().equals(ruleId)).toList();
            assertThat(found).as("limits of " + ruleId).hasSize(1);
            return found.get(0);
        }

        @Test
        void theThreeCountsAreDeclaredFromTheRulesetInItems() {
            assertThat(limitOf("customization_text_input_count")).isEqualTo(new RuleLimit(
                    "customization.surfaces[].text_inputs", "customization_text_input_count", Limit.Kind.MAX_COUNT,
                    max("customization_text_input_count"), Limit.Unit.ITEMS, Severity.WARNING, SourceStatus.SECONDARY, true));
            assertThat(limitOf("customization_dropdown_options")).isEqualTo(new RuleLimit(
                    "customization.surfaces[].dropdowns[].options", "customization_dropdown_options", Limit.Kind.MAX_COUNT,
                    max("customization_dropdown_options"), Limit.Unit.ITEMS, Severity.WARNING, SourceStatus.SECONDARY, true));
            assertThat(limitOf("customization_image_input_count")).isEqualTo(new RuleLimit(
                    "customization.surfaces[].image_inputs", "customization_image_input_count", Limit.Kind.MAX_COUNT,
                    max("customization_image_input_count"), Limit.Unit.ITEMS, Severity.WARNING, SourceStatus.SECONDARY, true));
        }

        @Test
        void aRuleWithNoNumberDeclaresNoLimit() {
            assertThat(engine.limits(CONFIG, null)).extracting(RuleLimit::ruleId)
                    .doesNotContain("customization_text_input_max_chars", "customization_free_text_sensitive");
        }

        @Test
        void aProductTypeOverrideChangesTheLimitDeclaredAndTheOneEnforced() {
            RulesetConfig test = RulesetLoader.loadClasspath("rulesets/amazon-test-categories.yaml");
            int base = test.rule("customization_image_input_count").intParam("max_count");
            int mug = test.rule("customization_image_input_count", "TESTMUG").intParam("max_count");
            assertThat(mug).isNotEqualTo(base);

            assertThat(engine.limits(test, null)).filteredOn(l -> l.ruleId().equals("customization_image_input_count"))
                    .extracting(RuleLimit::value).containsExactly(base);
            assertThat(engine.limits(test, "testmug")).filteredOn(l -> l.ruleId().equals("customization_image_input_count"))
                    .extracting(RuleLimit::value).containsExactly(mug);

            Listing atMugLimit = ofProductType("TESTMUG", surface("Front", List.of(), List.of(), mug));
            Listing overMugLimit = ofProductType("TESTMUG", surface("Front", List.of(), List.of(), mug + 1));
            Listing otherType = ofProductType("LUGGAGE", surface("Front", List.of(), List.of(), mug));
            assertThat(engine.run(atMugLimit, test)).extracting(Finding::ruleId).doesNotContain("customization_image_input_count");
            assertThat(engine.run(overMugLimit, test)).extracting(Finding::ruleId).contains("customization_image_input_count");
            assertThat(engine.run(otherType, test)).extracting(Finding::ruleId).contains("customization_image_input_count");
        }

        private Listing ofProductType(String productType, CustomizationSurface surface) {
            Listing base = withCustomization(surface);
            return new Listing(base.title(), base.description(), base.tags(), base.price(), base.category(), base.materials(),
                    base.images(), base.quantity(), base.whoMade(), base.whenMade(), base.brand(), base.bulletPoints(),
                    base.searchTerms(), productType, base.itemHighlights(), List.of(), base.customizationSurfaces());
        }
    }
}
