package com.thanhdatpb.listingguard.trademark.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TestRulesets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TrademarkRulesetLoaderTest {

    private static final String STATUS = "status: unverified";

    @Test
    void loadsTheShippedRuleset() {
        TrademarkRuleset ruleset = TestRulesets.shipped();

        assertThat(ruleset.packId()).isEqualTo("trademark-risk");
        assertThat(ruleset.version()).isEqualTo("trademark-risk-2026-11b");
        assertThat(ruleset.checkedAt()).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(ruleset.contentHash()).matches("[0-9a-f]{64}");
        assertThat(ruleset.ruleOrder()).containsExactlyElementsOf(RuleIds.ALL);
        assertThat(ruleset.catalogExact().risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(ruleset.catalogFuzzy().risk()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(ruleset.geoSport().risk()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(ruleset.pureColor().risk()).isEqualTo(RiskLevel.LOW);
        assertThat(ruleset.matching().candidateThreshold()).isEqualTo(0.30);
        assertThat(ruleset.matching().finalThreshold()).isEqualTo(0.80);
        assertThat(ruleset.lexicon().places()).contains(List.of("northvale"), List.of("new", "haverton"));
    }

    @Test
    void loadsTheTestRulesetWithDifferentValues() {
        TrademarkRuleset test = TestRulesets.test();
        TrademarkRuleset shipped = TestRulesets.shipped();

        assertThat(test.geoSport().maxTokenDistance()).isNotEqualTo(shipped.geoSport().maxTokenDistance());
        assertThat(test.lexicon().places()).isNotEqualTo(shipped.lexicon().places());
        assertThat(test.contentHash()).isNotEqualTo(shipped.contentHash());
    }

    @Test
    void contentHashIgnoresLineEndingsAndBom() {
        String text = TestRulesets.text(TestRulesets.TEST);

        String crlf = TrademarkRulesetLoader.parse(text.replace("\n", "\r\n")).contentHash();
        String bom = TrademarkRulesetLoader.parse("\uFEFF" + text).contentHash();

        assertThat(crlf).isEqualTo(TestRulesets.test().contentHash());
        assertThat(bom).isEqualTo(TestRulesets.test().contentHash());
    }

    @Test
    void anyChangeChangesTheContentHash() {
        String changed = TestRulesets.variant("final_threshold: 0.80", "final_threshold: 0.81").contentHash();

        assertThat(changed).isNotEqualTo(TestRulesets.test().contentHash());
    }

    /** ADR-002: a published version is immutable. If this fails, someone edited trademark-risk-2026-11. */
    @Test
    void previousRulesetIsFrozen() {
        assertThat(TestRulesets.previous().version()).isEqualTo("trademark-risk-2026-11");
        assertThat(TestRulesets.previous().contentHash())
                .isEqualTo("57c8523ad2306c7f1058a79df6006a042395764b1d9dca4d69c9a9c715274707");
    }

    /** ADR-002: if this fails, someone edited trademark-risk-2026-11b after it was published; add a new version. */
    @Test
    void shippedRulesetIsFrozen() {
        assertThat(TestRulesets.shipped().contentHash())
                .isEqualTo("5e2dbfc50b0f08f9a3382fd47ecd53b37c0ef3058231df146e83d4a79b496fe8");
    }

    @Test
    void theShippedRulesetIsThePreviousOnePlusTheThreeOptionalSections() {
        TrademarkRuleset previous = TestRulesets.previous();
        TrademarkRuleset shipped = TestRulesets.shipped();

        assertThat(shipped.normalization()).isEqualTo(previous.normalization());
        assertThat(shipped.matching()).isEqualTo(previous.matching());
        assertThat(shipped.lexicon()).isEqualTo(previous.lexicon());
        assertThat(shipped.ruleOrder()).isEqualTo(previous.ruleOrder());
        assertThat(shipped.catalogExact()).isEqualTo(previous.catalogExact());
        assertThat(shipped.catalogPhrase()).isEqualTo(previous.catalogPhrase());
        assertThat(shipped.catalogFuzzy()).isEqualTo(previous.catalogFuzzy());
        assertThat(shipped.geoSport()).isEqualTo(previous.geoSport());
        assertThat(shipped.colorContainsMark()).isEqualTo(previous.colorContainsMark());
        assertThat(shipped.pureColor()).isEqualTo(previous.pureColor());
        assertThat(shipped.disclaimer()).isEqualTo(previous.disclaimer());
    }

    @Test
    void aRulesetPublishedBeforeTheOptionalSectionsLoadsWithoutThem() {
        TrademarkRuleset previous = TestRulesets.previous();

        assertThat(previous.productClasses()).isSameAs(TrademarkRuleset.ProductClasses.NONE);
        assertThat(previous.genericWords()).isSameAs(TrademarkRuleset.GenericWords.NONE);
        assertThat(previous.sourceLinks()).isSameAs(TrademarkRuleset.SourceLinks.NONE);
    }

    /** The product types and Nice classes the maintainer supplied (all unverified); the frozen hash pins them too. */
    @Test
    void theShippedRulesetDeclaresTheMaintainersProductTypes() {
        TrademarkRuleset.ProductClasses classes = TestRulesets.shipped().productClasses();

        assertThat(classes.downgradeLevels()).isEqualTo(1);
        assertThat(classes.types()).containsExactly(
                Map.entry("apparel", List.of(25)), Map.entry("mug", List.of(21)),
                Map.entry("poster", List.of(16)), Map.entry("sticker", List.of(16)),
                Map.entry("phone_case", List.of(9)), Map.entry("jewelry", List.of(14)),
                Map.entry("bag", List.of(18)), Map.entry("furniture_decor", List.of(20)),
                Map.entry("fabric", List.of(24)), Map.entry("toy", List.of(28)));
    }

    @Test
    void theShippedRulesetListsNoGenericWordUntilTheMaintainerHasReviewedThem() {
        TrademarkRuleset.GenericWords generic = TestRulesets.shipped().genericWords();

        assertThat(generic.words()).isEmpty();
        assertThat(generic.contextFields()).containsExactly("brand", "shop_name");
        assertThat(generic.maxRiskWithoutContext()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void theClassesOfAProductTypeAreSortedWhateverTheOrderInTheFile() {
        assertThat(TestRulesets.test().productClasses().types().get("banner")).containsExactly(16, 24);
    }

    @Test
    void anEmptyGenericWordListIsAllowed() {
        TrademarkRuleset empty = TestRulesets.variant("  words:\n    - Plumb\n    - Harbor Lamp\n", "  words: []\n");

        assertThat(empty.genericWords().words()).isEmpty();
    }

    @Test
    void genericWordsAreNormalizedLikeCatalogMarks() {
        assertThat(TestRulesets.test().genericWords().words()).containsExactlyInAnyOrder("plumb", "harbor lamp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"shirt: [25, 25]", "shirt: [0]", "shirt: [46]", "shirt: []", "shirt: 25", "Shirt: [25]",
            "shirt-type: [25]", "9shirt: [25]"})
    void rejectsAProductTypeThatIsMalformed(String replacement) {
        assertThatThrownBy(() -> TestRulesets.variant("    shirt: [25, 35]\n", "    " + replacement + "\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("product_classes.types");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "3", "-1"})
    void rejectsADowngradeOutsideOneToTwoLevels(String levels) {
        assertThatThrownBy(() -> TestRulesets.variant("out_of_class_downgrade_levels: 1",
                "out_of_class_downgrade_levels: " + levels))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("out_of_class_downgrade_levels");
    }

    @Test
    void theProductClassesSectionNeedsAtLeastOneTypeAndKnownPlaceholders() {
        assertThatThrownBy(() -> TestRulesets.variant("  types:\n    shirt: [25, 35]\n    cup: [21]\n    banner: [24, 16]\n"
                + "    gadget: [9]\n", "  types: {}\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("non-empty mapping");
        assertThatThrownBy(() -> TestRulesets.variant("của '{product_type}'", "của '{brand}'"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("unknown placeholder '{brand}'");
    }

    @Test
    void rejectsADuplicateGenericWordAfterNormalizationAndAWordWithNoLetters() {
        assertThatThrownBy(() -> TestRulesets.variant("    - Plumb\n", "    - Plumb\n    - PLÜMB\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> TestRulesets.variant("    - Plumb\n", "    - Plumb\n    - \"!!\"\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("no letters or digits");
    }

    @Test
    void rejectsAGenericWordsSectionWithAnUnknownContextFieldOrCap() {
        assertThatThrownBy(() -> TestRulesets.variant("    - shop_name\n  max_risk", "    - seller\n  max_risk"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("unknown field 'seller'");
        assertThatThrownBy(() -> TestRulesets.variant("    - brand\n    - shop_name\n  max_risk",
                "    - brand\n    - brand\n  max_risk"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> TestRulesets.variant("max_risk_without_context: LOW", "max_risk_without_context: UNKNOWN"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("max_risk_without_context");
        assertThatThrownBy(() -> TestRulesets.variant("không đứng ở {context_fields}", "không đứng ở {fields}"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("unknown placeholder '{fields}'");
    }

    static Stream<Arguments> badUrlTemplates() {
        return Stream.of(
                Arguments.of("http://registry.example.test/{id}", "https://"),
                Arguments.of("https://registry.example.test/", "exactly once"),
                Arguments.of("https://registry.example.test/{id}/{id}", "exactly once"),
                Arguments.of("https://{id}.example.test/", "plain host"),
                Arguments.of("https://registry.example.test/{id}/{other}", "stray"),
                Arguments.of("https://registry.example.test/a b/{id}", "whitespace"),
                Arguments.of("https://user@registry.example.test/{id}", "plain host"),
                Arguments.of("https:///{id}", "plain host"),
                Arguments.of("https://registry.example.test/" + "a".repeat(300) + "{id}",
                        "300"));
    }

    @ParameterizedTest
    @MethodSource("badUrlTemplates")
    void rejectsAnUrlTemplateThatCouldSendAnIdSomewhereElse(String url, String expected) {
        assertThatThrownBy(() -> TestRulesets.variant("\"https://other.example.test/{id}\"", "\"" + url + "\""))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining(expected);
    }

    @Test
    void rejectsABadSourceKeyPatternOrDuplicateKey() {
        assertThatThrownBy(() -> TestRulesets.variant("source_key: other.src", "source_key: Other"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("source_key must match");
        assertThatThrownBy(() -> TestRulesets.variant("source_key: other.src", "source_key: tsreg"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> TestRulesets.variant("id_pattern: \"[A-Z]{2}-[0-9]{4}\"", "id_pattern: \"[A-Z\""))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("regular expression");
        assertThatThrownBy(() -> TestRulesets.variant("      label: Other registry\n", ""))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("label");
    }

    @Test
    void anOptionalSectionStillRejectsUnknownKeys() {
        assertThatThrownBy(() -> TestRulesets.variant("  words:\n    - Plumb", "  extra: 1\n  words:\n    - Plumb"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("Unknown key 'extra'");
        assertThatThrownBy(() -> TestRulesets.variant("      label: Test registry\n",
                "      label: Test registry\n      verified: true\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("Unknown key 'verified'");
    }

    @Test
    void rejectsAnUnknownTopLevelKey() {
        assertThatThrownBy(() -> TestRulesets.variant("disclaimer:", "extra: 1\ndisclaimer:"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("Unknown key 'extra'");
    }

    @Test
    void rejectsAnUnknownKeyInsideASection() {
        assertThatThrownBy(() -> TestRulesets.variant("max_ngram_tokens: 3", "max_ngram_tokens: 3\n  max_ngram: 3"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("Unknown key 'max_ngram' in matching");
    }

    @Test
    void rejectsAnUnknownKeyInsideARule() {
        assertThatThrownBy(() -> TestRulesets.variant("min_tokens: 2", "min_tokens: 2\n    min_token: 2"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("Unknown key 'min_token'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pack_id", "version", "checked_at", "disclaimer", "normalization", "matching", "lexicon",
            "rules"})
    void rejectsAMissingTopLevelKey(String key) {
        String text = TestRulesets.text(TestRulesets.TEST);
        String without = text.replaceFirst("(?m)^" + key + ":", "renamed_" + key + ":");

        assertThatThrownBy(() -> TrademarkRulesetLoader.parse(without))
                .isInstanceOf(TrademarkRulesetException.class);
    }

    @Test
    void rejectsAMissingRequiredKey() {
        assertThatThrownBy(() -> TestRulesets.variant("final_threshold: 0.80", "final_threshold_x: 0.80"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("final_threshold");
    }

    @Test
    void rejectsADuplicateKey() {
        assertThatThrownBy(() -> TestRulesets.variant("max_ngram_tokens: 3", "max_ngram_tokens: 3\n  max_ngram_tokens: 2"))
                .isInstanceOf(TrademarkRulesetException.class);
    }

    static List<Integer> sourceOccurrences() {
        String text = TestRulesets.text(TestRulesets.TEST);
        return IntStream.range(0, text.split(STATUS, -1).length - 1).boxed().toList();
    }

    /** ADR-009: startup validation rejects the rules if source.status is not unverified. Every source block is checked. */
    @ParameterizedTest
    @MethodSource("sourceOccurrences")
    void rejectsAnySourceThatIsNotUnverified(int occurrence) {
        for (String status : List.of("verified", "secondary")) {
            String text = TestRulesets.text(TestRulesets.TEST);
            int at = -1;
            for (int i = 0; i <= occurrence; i++) {
                at = text.indexOf(STATUS, at + 1);
            }
            String changed = text.substring(0, at) + "status: " + status + text.substring(at + STATUS.length());

            assertThatThrownBy(() -> TrademarkRulesetLoader.parse(changed))
                    .as("occurrence %d as %s", occurrence, status)
                    .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("must be 'unverified'");
        }
    }

    @Test
    void everySourceBlockOfTheRulesetIsCovered() {
        // twelve blocks: normalization, matching, lexicon, product_classes, generic_words, source_links and the six rules
        assertThat(sourceOccurrences()).hasSize(12);
    }

    @Test
    void rejectsASourceKeyOtherThanStatusAndNote() {
        assertThatThrownBy(() -> TestRulesets.variant("status: unverified\n    note: Internal normalization choices",
                "status: unverified\n    urls: [x]\n    note: Internal normalization choices"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("Unknown key 'urls'");
    }

    @Test
    void rejectsAPackIdOtherThanTrademarkRisk() {
        assertThatThrownBy(() -> TestRulesets.variant("pack_id: trademark-risk", "pack_id: listing-rules"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("pack_id must be 'trademark-risk'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "SEVERE"})
    void rejectsARiskLevelThatIsNotLowMediumOrHigh(String risk) {
        assertThatThrownBy(() -> TestRulesets.variant("    risk: HIGH\n", "    risk: " + risk + "\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("risk must be LOW, MEDIUM or HIGH");
    }

    @Test
    void pureColorDescriptionMustBeLow() {
        assertThatThrownBy(() -> TestRulesets.variant("    risk: LOW\n", "    risk: MEDIUM\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("risk must be LOW");
    }

    @Test
    void rejectsAnUnknownPlaceholder() {
        assertThatThrownBy(() -> TestRulesets.variant("Từ '{token}'", "Từ '{brand}'"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("unknown placeholder '{brand}'");
    }

    @Test
    void fuzzyScoreIsOnlyAllowedInTheFuzzyRule() {
        assertThatThrownBy(() -> TestRulesets.variant("Từ '{token}'", "Từ '{score}'"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("unknown placeholder '{score}'");
    }

    @Test
    void rejectsAStrayBrace() {
        assertThatThrownBy(() -> TestRulesets.variant("không làm giảm mức rủi ro.", "không làm giảm {mức rủi ro."))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("stray");
    }

    @Test
    void rejectsAScoringAlgorithmTheCodeDoesNotImplement() {
        assertThatThrownBy(() -> TestRulesets.variant("scoring_algorithm_version: osa-similarity-1",
                "scoring_algorithm_version: jaro-winkler-1"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("this build implements");
    }

    @Test
    void rejectsAColorRuleDeclaredBeforeTheCatalogRulesItReads() {
        String text = TestRulesets.text(TestRulesets.TEST);
        int pure = text.indexOf("  pure_color_description:");
        int exact = text.indexOf("  catalog_exact:");
        String moved = text.substring(0, exact) + text.substring(pure) + "\n" + text.substring(exact, pure);

        assertThatThrownBy(() -> TrademarkRulesetLoader.parse(moved))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("before 'pure_color_description'");
    }

    @Test
    void rejectsAnUnknownAndAMissingRule() {
        assertThatThrownBy(() -> TestRulesets.variant("  catalog_fuzzy:\n", "  catalog_fuzzy_x:\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("unknown rule 'catalog_fuzzy_x'");
    }

    @Test
    void rejectsAColorWordThatIsSeveralWords() {
        assertThatThrownBy(() -> TestRulesets.variant("    - navy\n", "    - navy blue\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("must be a single word");
    }

    @Test
    void rejectsADuplicateLexiconEntryAfterNormalization() {
        assertThatThrownBy(() -> TestRulesets.variant("    - Aldwick\n", "    - Aldwick\n    - ALDWÍCK\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("twice");
    }

    @Test
    void rejectsAnUnknownScanField() {
        assertThatThrownBy(() -> TestRulesets.variant("    - shop_name\n", "    - seller\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("unknown field 'seller'");
    }

    @Test
    void rejectsFuzzyMinCharsAboveFuzzyMaxChars() {
        assertThatThrownBy(() -> TestRulesets.variant("fuzzy_min_chars: 5", "fuzzy_min_chars: 50"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("fuzzy_min_chars");
    }

    @Test
    void rejectsAThresholdOutsideZeroToOne() {
        assertThatThrownBy(() -> TestRulesets.variant("final_threshold: 0.80", "final_threshold: 1.5"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("final_threshold");
    }

    @Test
    void rejectsAMultiCharacterMappingKey() {
        assertThatThrownBy(() -> TestRulesets.variant("\"0\": \"o\"", "\"00\": \"o\""))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("single character");
    }

    @Test
    void rejectsNonYamlAndNonMapping() {
        assertThatThrownBy(() -> TrademarkRulesetLoader.parse("- a\n- b\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("mapping");
        assertThatThrownBy(() -> TrademarkRulesetLoader.loadClasspath("rulesets/does-not-exist.yaml"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("not found");
    }
}
