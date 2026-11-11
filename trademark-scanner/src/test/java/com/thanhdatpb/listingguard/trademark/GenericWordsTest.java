package com.thanhdatpb.listingguard.trademark;

import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.brandAndType;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.mark;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.scannerOf;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.titleAndType;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-009, "Product classes, source links and generic words": a catalog mark that is also an ordinary word keeps its level
 * only as an exact or phrase match in a context field; anywhere else it is capped, and the evidence says so with the
 * level the rule assigned. The word list is synthetic and is read from the test ruleset.
 */
class GenericWordsTest {

    private final TrademarkRuleset ruleset = TestRulesets.test();
    private final TrademarkRuleset.GenericWords generic = ruleset.genericWords();

    private String singleWord() {
        return generic.words().stream().filter(word -> !word.contains(" ")).findFirst().orElseThrow();
    }

    private String phrase() {
        return generic.words().stream().filter(word -> word.contains(" ")).findFirst().orElseThrow();
    }

    private static String capitalized(String normalized) {
        StringBuilder out = new StringBuilder();
        for (String word : normalized.split(" ")) {
            out.append(out.length() == 0 ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private TrademarkScanner scanner(TrademarkRuleset rules, String display) {
        return scannerOf(rules, mark(rules, display, List.of(25)));
    }

    private static TrademarkFinding only(TrademarkResult result) {
        assertThat(result.findings()).hasSize(1);
        return result.findings().get(0);
    }

    @Test
    void theTestRulesetListsSyntheticWordsAndTheShippedOneListsNone() {
        assertThat(generic.words()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(generic.contextFields()).containsExactly("brand", "shop_name");
        assertThat(generic.maxRiskWithoutContext()).isEqualTo(RiskLevel.LOW);
        // nothing changes in production until the maintainer has reviewed each word (ADR-009)
        assertThat(TestRulesets.shipped().genericWords().words()).isEmpty();
        assertThat(TestRulesets.previous().genericWords().words()).isEmpty();
    }

    @Test
    void aGenericMarkOutsideTheContextFieldsIsCappedAndTheOriginalLevelIsShown() {
        String word = capitalized(singleWord());
        TrademarkResult result = scanner(ruleset, word).scan(TrademarkCheckInput.ofTitle("Big " + word + " Shirt"));

        TrademarkFinding finding = only(result);
        assertThat(finding.risk()).isEqualTo(generic.maxRiskWithoutContext());
        assertThat(result.riskLevel()).isEqualTo(generic.maxRiskWithoutContext());
        assertThat(finding.evidence().riskAdjustments()).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.reason()).isEqualTo(AdjustmentReason.COMMON_WORD);
            assertThat(adjustment.reason().code()).isEqualTo("common_word");
            assertThat(adjustment.from()).isEqualTo(ruleset.catalogExact().risk());
            assertThat(adjustment.to()).isEqualTo(generic.maxRiskWithoutContext());
            assertThat(adjustment.note()).contains("'" + word + "'", ruleset.catalogExact().risk().name(),
                    generic.maxRiskWithoutContext().name(), "brand, shop_name");
        });
        assertThat(finding.message()).endsWith(finding.evidence().riskAdjustments().get(0).note());
    }

    @Test
    void anExactMatchInAContextFieldKeepsItsLevel() {
        String word = capitalized(singleWord());
        TrademarkScanner scanner = scanner(ruleset, word);

        for (String field : generic.contextFields()) {
            TrademarkCheckInput input = field.equals("brand") ? brandAndType(word, null)
                    : new TrademarkCheckInput(null, List.of(), List.of(), null, null, word, null, null, null);
            TrademarkFinding finding = only(scanner.scan(input));
            assertThat(finding.evidence().field()).as(field).isEqualTo(field);
            assertThat(finding.risk()).as(field).isEqualTo(ruleset.catalogExact().risk());
            assertThat(finding.evidence().riskAdjustments()).as(field).isEmpty();
        }
    }

    @Test
    void aPhraseMatchIsHeldToTheSameRule() {
        String phrase = capitalized(phrase());
        TrademarkScanner scanner = scanner(ruleset, phrase);

        TrademarkFinding inBrand = only(scanner.scan(brandAndType(phrase + " Co", null)));
        TrademarkFinding inTitle = only(scanner.scan(TrademarkCheckInput.ofTitle("The " + phrase + " set")));

        assertThat(inBrand.evidence().matchType()).isEqualTo(MatchType.PHRASE);
        assertThat(inBrand.risk()).isEqualTo(ruleset.catalogPhrase().risk());
        assertThat(inTitle.evidence().matchType()).isEqualTo(MatchType.PHRASE);
        assertThat(inTitle.risk()).isEqualTo(generic.maxRiskWithoutContext());
    }

    @Test
    void aFuzzyMatchOfAGenericMarkIsNeverInContext() {
        String word = capitalized(singleWord());
        String misspelled = word + word.charAt(word.length() - 1);
        TrademarkScanner scanner = scanner(ruleset, word);

        TrademarkFinding inBrand = only(scanner.scan(brandAndType(misspelled, null)));

        assertThat(inBrand.evidence().matchType()).isEqualTo(MatchType.FUZZY);
        assertThat(inBrand.risk()).isEqualTo(generic.maxRiskWithoutContext());
        assertThat(inBrand.evidence().riskAdjustments()).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.reason()).isEqualTo(AdjustmentReason.COMMON_WORD);
            assertThat(adjustment.from()).isEqualTo(ruleset.catalogFuzzy().risk());
        });
    }

    @Test
    void aMarkThatIsNotAGenericWordIsUnchanged() {
        TrademarkFinding finding = only(scanner(ruleset, "Zorvex").scan(TrademarkCheckInput.ofTitle("Big Zorvex Shirt")));

        assertThat(finding.risk()).isEqualTo(ruleset.catalogExact().risk());
        assertThat(finding.evidence().riskAdjustments()).isEmpty();
    }

    @Test
    void theContextFieldsComeFromTheRuleset() {
        TrademarkRuleset titleOnly = TestRulesets.variant("  context_fields:\n    - brand\n    - shop_name\n",
                "  context_fields:\n    - title\n");
        String word = capitalized(singleWord());
        TrademarkScanner scanner = scanner(titleOnly, word);

        assertThat(only(scanner.scan(TrademarkCheckInput.ofTitle(word))).risk())
                .isEqualTo(titleOnly.catalogExact().risk());
        assertThat(only(scanner.scan(brandAndType(word, null))).risk())
                .isEqualTo(titleOnly.genericWords().maxRiskWithoutContext());
    }

    @Test
    void theCapComesFromTheRuleset() {
        TrademarkRuleset capped = TestRulesets.variant("max_risk_without_context: LOW", "max_risk_without_context: MEDIUM");
        String word = capitalized(singleWord());
        TrademarkScanner scanner = scanner(capped, word);

        TrademarkFinding exact = only(scanner.scan(TrademarkCheckInput.ofTitle("Big " + word)));

        assertThat(exact.risk()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(exact.evidence().riskAdjustments()).singleElement()
                .satisfies(adjustment -> assertThat(adjustment.to()).isEqualTo(RiskLevel.MEDIUM));
        // a fuzzy match is MEDIUM already: nothing is lowered, so nothing is claimed
        String misspelled = word + word.charAt(word.length() - 1);
        TrademarkFinding fuzzy = only(scanner.scan(TrademarkCheckInput.ofTitle(misspelled)));
        assertThat(fuzzy.risk()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(fuzzy.evidence().riskAdjustments()).isEmpty();
    }

    @Test
    void bothReasonsAreListedFromTheOriginalLevelAndTheLowestWins() {
        TrademarkRuleset.ProductClasses classes = ruleset.productClasses();
        String word = capitalized(singleWord());
        // a class that is not the type's: the type "cup" has class 21 only
        int outside = classes.classesOf("cup").contains(9) ? 10 : 9;
        TrademarkScanner scanner = scannerOf(ruleset, mark(ruleset, word, List.of(outside)));

        TrademarkFinding finding = only(scanner.scan(titleAndType("Big " + word, "cup")));

        RiskLevel base = ruleset.catalogExact().risk();
        assertThat(finding.evidence().riskAdjustments()).extracting(RiskAdjustment::reason)
                .containsExactly(AdjustmentReason.OUTSIDE_PRODUCT_CLASSES, AdjustmentReason.COMMON_WORD);
        assertThat(finding.evidence().riskAdjustments()).extracting(RiskAdjustment::from).containsOnly(base);
        assertThat(finding.risk()).isEqualTo(generic.maxRiskWithoutContext());
        assertThat(finding.evidence().riskAdjustments()).extracting(RiskAdjustment::to)
                .allSatisfy(to -> assertThat(to).isGreaterThanOrEqualTo(finding.risk()));
    }

    @Test
    void aRulesetWithNoWordChangesNothing() {
        TrademarkRuleset shipped = TestRulesets.shipped();
        String word = capitalized(singleWord());

        TrademarkFinding finding = only(scanner(shipped, word).scan(TrademarkCheckInput.ofTitle("Big " + word)));

        assertThat(finding.risk()).isEqualTo(shipped.catalogExact().risk());
        assertThat(finding.evidence().riskAdjustments()).isEmpty();
    }
}
