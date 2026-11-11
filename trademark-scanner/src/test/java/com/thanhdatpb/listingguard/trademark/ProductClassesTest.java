package com.thanhdatpb.listingguard.trademark;

import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.color;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.mark;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.scannerOf;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.titleAndType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.config.UnknownProductTypeException;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * ADR-009, "Product classes, source links and generic words": a request may name a product type; a catalog match whose
 * mark has Nice classes, none of them the product type's, is reported lower. Product types, classes and the number of
 * levels are read from the test ruleset, never assumed.
 */
class ProductClassesTest {

    private static final String TYPE = "shirt";

    private final TrademarkRuleset ruleset = TestRulesets.test();
    private final TrademarkRuleset.ProductClasses classes = ruleset.productClasses();

    private int insideClass() {
        return classes.classesOf(TYPE).stream().sorted().findFirst().orElseThrow();
    }

    /** A Nice class that is not one of the product type's. */
    private int outsideClass() {
        Set<Integer> inside = classes.classesOf(TYPE);
        return IntStream.rangeClosed(1, 45).filter(number -> !inside.contains(number)).findFirst().orElseThrow();
    }

    private static RiskLevel lowered(RiskLevel base, int levels) {
        return RiskLevel.values()[Math.max(0, base.ordinal() - levels)];
    }

    private TrademarkFinding only(TrademarkResult result) {
        assertThat(result.findings()).hasSize(1);
        return result.findings().get(0);
    }

    @Test
    void theTestRulesetDeclaresProductTypesWithSeveralClasses() {
        assertThat(classes.knownTypes()).contains(TYPE, "cup", "banner", "gadget");
        assertThat(classes.classesOf(TYPE)).hasSizeGreaterThan(1);
        assertThat(classes.downgradeLevels()).isPositive();
    }

    @Test
    void withoutAProductTypeAMatchKeepsTheLevelItsRuleAssigns() {
        TrademarkScanner scanner = scannerOf(ruleset, mark(ruleset, "Zorvex", List.of(outsideClass())));

        TrademarkFinding finding = only(scanner.scan(titleAndType("Big Zorvex Shirt", null)));

        assertThat(finding.risk()).isEqualTo(ruleset.catalogExact().risk());
        assertThat(finding.evidence().riskAdjustments()).isEmpty();
        assertThat(finding.message()).doesNotContain("hạ từ");
    }

    @Test
    void aMarkInOneOfTheProductTypesClassesKeepsItsLevel() {
        TrademarkScanner scanner = scannerOf(ruleset,
                mark(ruleset, "Zorvex", List.of(insideClass())),
                mark(ruleset, "Quillmark", List.of(outsideClass(), insideClass())));

        TrademarkResult result = scanner.scan(titleAndType("Zorvex Quillmark Shirt", TYPE));

        assertThat(result.findings()).hasSize(2).allSatisfy(finding -> {
            assertThat(finding.risk()).isEqualTo(ruleset.catalogExact().risk());
            assertThat(finding.evidence().riskAdjustments()).isEmpty();
        });
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogExact().risk());
    }

    @Test
    void aMarkOutsideTheProductTypesClassesDropsByTheConfiguredLevelsAndSaysWhy() {
        int outside = outsideClass();
        TrademarkScanner scanner = scannerOf(ruleset, mark(ruleset, "Zorvex", List.of(outside)));
        RiskLevel base = ruleset.catalogExact().risk();
        RiskLevel expected = lowered(base, classes.downgradeLevels());

        TrademarkResult result = scanner.scan(titleAndType("Big Zorvex Shirt", TYPE));

        TrademarkFinding finding = only(result);
        assertThat(finding.risk()).isEqualTo(expected).isNotEqualTo(base);
        assertThat(result.riskLevel()).isEqualTo(expected);
        assertThat(finding.evidence().riskAdjustments()).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.reason()).isEqualTo(AdjustmentReason.OUTSIDE_PRODUCT_CLASSES);
            assertThat(adjustment.reason().code()).isEqualTo("outside_product_classes");
            assertThat(adjustment.from()).isEqualTo(base);
            assertThat(adjustment.to()).isEqualTo(expected);
            String productClasses = classes.classesOf(TYPE).stream().sorted().map(String::valueOf)
                    .collect(Collectors.joining(", "));
            assertThat(adjustment.note()).contains("'Zorvex'", String.valueOf(outside), productClasses,
                    "'" + TYPE + "'", base.name(), expected.name());
        });
        assertThat(finding.message()).endsWith(finding.evidence().riskAdjustments().get(0).note());
    }

    @Test
    void aFuzzyMatchOutsideTheClassesDropsToo() {
        TrademarkScanner scanner = scannerOf(ruleset, mark(ruleset, "Quillmark", List.of(outsideClass())));

        TrademarkFinding finding = only(scanner.scan(titleAndType("Quilmark Mug", TYPE)));

        assertThat(finding.evidence().matchType()).isEqualTo(MatchType.FUZZY);
        assertThat(finding.risk()).isEqualTo(lowered(ruleset.catalogFuzzy().risk(), classes.downgradeLevels()));
        assertThat(finding.evidence().riskAdjustments()).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.from()).isEqualTo(ruleset.catalogFuzzy().risk());
            assertThat(adjustment.to()).isEqualTo(finding.risk());
        });
    }

    @Test
    void theNumberOfLevelsComesFromTheRulesetAndNeverGoesBelowLow() {
        TrademarkRuleset twoLevels = TestRulesets.variant("out_of_class_downgrade_levels: 1",
                "out_of_class_downgrade_levels: 2");
        assertThat(twoLevels.productClasses().downgradeLevels()).isEqualTo(2);
        TrademarkScanner scanner = scannerOf(twoLevels,
                mark(twoLevels, "Zorvex", List.of(outsideClass())),
                mark(twoLevels, "Quillmark", List.of(outsideClass())));

        TrademarkResult exact = scanner.scan(titleAndType("Zorvex", TYPE));
        TrademarkResult fuzzy = scanner.scan(titleAndType("Quilmark", TYPE));

        assertThat(only(exact).risk()).isEqualTo(lowered(twoLevels.catalogExact().risk(), 2));
        assertThat(only(fuzzy).risk()).isEqualTo(RiskLevel.LOW);
        // a fuzzy MEDIUM only had one level to lose: the adjustment says MEDIUM to LOW, not more
        assertThat(only(fuzzy).evidence().riskAdjustments().get(0).to()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void aMarkWithNoNiceClassIsNeverLowered() {
        TrademarkScanner scanner = scannerOf(ruleset, new CatalogMark("zorvex", "Zorvex"));

        TrademarkFinding finding = only(scanner.scan(titleAndType("Big Zorvex Shirt", TYPE)));

        assertThat(finding.risk()).isEqualTo(ruleset.catalogExact().risk());
        assertThat(finding.evidence().riskAdjustments()).isEmpty();
    }

    @Test
    void aMatchInsideAColorPhraseIsLoweredToo_andBothExplanationsAreShown() {
        TrademarkScanner scanner = scannerOf(ruleset, mark(ruleset, "Zorvex", List.of(outsideClass())));

        TrademarkFinding finding = only(scanner.scan(titleAndType("Zorvex " + color(ruleset, 0), TYPE)));

        assertThat(finding.evidence().insideColorPhrase()).isTrue();
        assertThat(finding.risk()).isEqualTo(lowered(ruleset.catalogExact().risk(), classes.downgradeLevels()));
        assertThat(finding.message()).contains(ruleset.colorContainsMark().note())
                .contains(finding.evidence().riskAdjustments().get(0).note());
    }

    @Test
    void onlyTheMarkOutsideTheClassesIsLoweredWhenThereAreSeveral() {
        TrademarkScanner scanner = scannerOf(ruleset,
                mark(ruleset, "Zorvex", List.of(insideClass())),
                mark(ruleset, "Brask", List.of(outsideClass())));

        TrademarkResult result = scanner.scan(titleAndType("Zorvex Brask Shirt", TYPE));

        assertThat(result.findings()).hasSize(2);
        TrademarkFinding zorvex = result.findings().get(0);
        TrademarkFinding brask = result.findings().get(1);
        assertThat(zorvex.evidence().matchedMark()).isEqualTo("Zorvex");
        assertThat(zorvex.risk()).isEqualTo(ruleset.catalogExact().risk());
        assertThat(brask.evidence().matchedMark()).isEqualTo("Brask");
        assertThat(brask.risk()).isLessThan(zorvex.risk());
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogExact().risk());
    }

    @Test
    void aProductTypeTheRulesetDoesNotDeclareIsRefusedNotIgnored() {
        TrademarkScanner scanner = scannerOf(ruleset, mark(ruleset, "Zorvex", List.of(outsideClass())));

        assertThatThrownBy(() -> scanner.scan(titleAndType("Zorvex", "spaceship")))
                .isInstanceOfSatisfying(UnknownProductTypeException.class,
                        e -> assertThat(e.known()).containsExactlyElementsOf(classes.knownTypes()));
        // the keys are exact: nothing is corrected
        assertThatThrownBy(() -> scanner.scan(titleAndType("Zorvex", "Shirt")))
                .isInstanceOf(UnknownProductTypeException.class);
    }

    @Test
    void theMessageOfTheRefusalIsMadeSafeAndShort() {
        String hostile = "x\n\u0000<script>" + "y".repeat(500);
        TrademarkScanner scanner = scannerOf(ruleset, mark(ruleset, "Zorvex", List.of(1)));

        assertThatThrownBy(() -> scanner.scan(titleAndType("Zorvex", hostile)))
                .isInstanceOfSatisfying(UnknownProductTypeException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain("\n", "\u0000", "<", "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy");
                    assertThat(e.getMessage().length()).isLessThan(300);
                });
    }

    @Test
    void aBlankProductTypeIsNoProductTypeAndSurroundingWhitespaceIsStripped() {
        TrademarkScanner scanner = scannerOf(ruleset, mark(ruleset, "Zorvex", List.of(outsideClass())));
        TrademarkResult none = scanner.scan(titleAndType("Zorvex", null));

        TrademarkResult blank = scanner.scan(titleAndType("Zorvex", "   "));
        TrademarkResult padded = scanner.scan(titleAndType("Zorvex", "  " + TYPE + " "));

        assertThat(blank.findings()).isEqualTo(none.findings());
        assertThat(blank.inputHash()).isEqualTo(none.inputHash());
        assertThat(only(padded).evidence().riskAdjustments()).hasSize(1);
    }

    @Test
    void aRulesetPublishedBeforeProductClassesDeclaresNoTypeSoEveryTypeIsRefused() {
        TrademarkRuleset previous = TestRulesets.previous();
        TrademarkScanner scanner = scannerOf(previous, mark(previous, "Zorvex", List.of(1)));

        assertThat(previous.productClasses().knownTypes()).isEmpty();
        assertThatThrownBy(() -> scanner.scan(titleAndType("Zorvex", TYPE)))
                .isInstanceOfSatisfying(UnknownProductTypeException.class, e -> assertThat(e.known()).isEmpty());
        // and without a type it scans as it always did
        assertThat(only(scanner.scan(titleAndType("Zorvex", null))).risk()).isEqualTo(previous.catalogExact().risk());
    }

    @Test
    void theRulesetsPublishedBeforeScanAsTheNewOneDoesWhenNoProductTypeIsGiven() {
        TrademarkRuleset previous = TestRulesets.previous();
        TrademarkRuleset shipped = TestRulesets.shipped();
        List<String> marks = List.of("Zorvex", "Quillmark", "Blue Harbor Tools");
        TrademarkScanner before = ScannerFixtures.scanner(previous, marks.toArray(String[]::new));
        TrademarkScanner after = ScannerFixtures.scanner(shipped, marks.toArray(String[]::new));
        List<String> titles = List.of("Big Zorvex Shirt", "Quilmark mug", "blue harbor tools set",
                "Northvale Baseball Jersey", "Royal Blue White Pinstripe", "plain text");

        for (String title : titles) {
            TrademarkResult a = before.scan(TrademarkCheckInput.ofTitle(title));
            TrademarkResult b = after.scan(TrademarkCheckInput.ofTitle(title));
            assertThat(b.findings()).as(title).isEqualTo(a.findings());
            assertThat(b.riskLevel()).as(title).isEqualTo(a.riskLevel());
            assertThat(b.completeness()).as(title).isEqualTo(a.completeness());
        }
    }
}
