package com.thanhdatpb.listingguard.trademark.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TestRulesets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TrademarkRulesetLoaderTest {

    private static final String STATUS = "status: unverified";

    @Test
    void loadsTheShippedRuleset() {
        TrademarkRuleset ruleset = TestRulesets.shipped();

        assertThat(ruleset.packId()).isEqualTo("trademark-risk");
        assertThat(ruleset.version()).isEqualTo("trademark-risk-2026-11");
        assertThat(ruleset.checkedAt()).isEqualTo(LocalDate.of(2026, 10, 30));
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
    void shippedRulesetIsFrozen() {
        assertThat(TestRulesets.shipped().contentHash())
                .isEqualTo("f3b43b0d3a935616ca1b26871d98b9631852b090b271bfd0cc614f81a4bc4b29");
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
        // nine blocks: normalization, matching, lexicon and the six rules
        assertThat(sourceOccurrences()).hasSize(9);
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
