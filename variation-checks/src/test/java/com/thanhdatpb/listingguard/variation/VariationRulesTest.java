package com.thanhdatpb.listingguard.variation;

import static com.thanhdatpb.listingguard.variation.VariationFixtures.CHECKER;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.PARENT_SKU;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.RULESET;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.apparelType;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.attributes;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.child;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.colorAttribute;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.etsy;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.of;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.ofKind;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.rangeClass;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.replacing;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.sizeAttribute;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.sizes;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.theme;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.validFamily;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.with;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.withChildren;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.withParent;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.without;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.variation.config.RuleIds;
import com.thanhdatpb.listingguard.variation.config.SourceStatus;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rules of ADR-015 on synthetic families. Lists, limits and names are read from the shipped ruleset. */
class VariationRulesTest {

    private static final VariationRuleset.Range RANGE = RULESET.apparel().range();

    private static VariationResult check(VariationInput input) {
        return CHECKER.check(input);
    }

    // ---- a clean family ----------------------------------------------------------------------------------------

    @Test
    void aShirtFamilyOfEverySizeInTwoColorsHasNoFinding() {
        VariationInput family = validFamily();

        assertThat(family.children()).hasSize(sizes().size() * 2);
        VariationResult result = check(family);

        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void theNamesOfTheAttributesAreMatchedIgnoringCase() {
        VariationInput family = validFamily();
        VariationInput.Child first = family.children().get(0);
        Map<String, String> shouting = new LinkedHashMap<>();
        first.attributes().forEach((name, value) -> shouting.put(name.toUpperCase(), value));

        assertThat(check(replacing(family, 0, child(first.sku(), first.parentSku(), first.title(), shouting))).findings())
                .isEmpty();
    }

    // ---- parent_child_structure ----------------------------------------------------------------------------------

    @Test
    void aChildThatLacksAnAttributeOfTheThemeIsReportedWithItsPosition() {
        VariationInput family = validFamily();
        VariationInput.Child broken = family.children().get(3);
        VariationInput.Child withoutSize = child(broken.sku(), broken.parentSku(), broken.title(),
                without(broken.attributes(), sizeAttribute()));

        List<VariationFinding> found = ofKind(check(replacing(family, 3, withoutSize)), RuleIds.CHILD_ATTRIBUTE_MISSING);

        assertThat(found).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo(RuleIds.PARENT_CHILD_STRUCTURE);
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.sourceStatus()).isEqualTo(SourceStatus.SECONDARY);
            assertThat(finding.field()).isEqualTo("children[3].attributes." + sizeAttribute());
            assertThat(finding.value()).isNull();
            assertThat(finding.message()).contains("thứ 4", "'" + sizeAttribute() + "'", "'" + theme().name() + "'");
        });
    }

    @Test
    void aChildThatPointsAtAnotherParentIsReportedWithBothSkus() {
        VariationInput family = validFamily();
        VariationInput.Child item = family.children().get(2);

        List<VariationFinding> found = ofKind(check(replacing(family, 2,
                child(item.sku(), "OTHER-P", item.title(), item.attributes()))), RuleIds.CHILD_PARENT_SKU_MISMATCH);

        assertThat(found).singleElement().satisfies(finding -> {
            assertThat(finding.field()).isEqualTo("children[2].parent_sku");
            assertThat(finding.value()).isEqualTo("OTHER-P");
            assertThat(finding.relatedFields()).containsExactly("parent.sku");
            assertThat(finding.message()).contains("'OTHER-P'", "'" + PARENT_SKU + "'", "thứ 3");
        });
    }

    @Test
    void aChildWithoutParentSkuIsReported() {
        VariationInput family = validFamily();
        VariationInput.Child item = family.children().get(0);

        List<VariationFinding> found = ofKind(check(replacing(family, 0,
                child(item.sku(), null, item.title(), item.attributes()))), RuleIds.CHILD_PARENT_SKU_MISSING);

        assertThat(found).singleElement().satisfies(finding -> {
            assertThat(finding.field()).isEqualTo("children[0].parent_sku");
            assertThat(finding.message()).contains("'" + PARENT_SKU + "'");
        });
    }

    @Test
    void noParentNoParentSkuAndNoThemeAreEachReported() {
        VariationInput family = validFamily();

        assertThat(ofKind(check(withParent(family, null)), RuleIds.PARENT_MISSING)).singleElement()
                .extracting(VariationFinding::field).isEqualTo("parent");
        VariationInput.Parent noSku = new VariationInput.Parent(null, apparelType().productType(),
                apparelType().themes().get(0), null);
        assertThat(ofKind(check(withParent(family, noSku)), RuleIds.PARENT_MISSING)).singleElement()
                .extracting(VariationFinding::field).isEqualTo("parent.sku");
        VariationInput.Parent noTheme = new VariationInput.Parent(PARENT_SKU, apparelType().productType(), null, null);
        VariationResult withoutTheme = check(withParent(family, noTheme));
        assertThat(ofKind(withoutTheme, RuleIds.PARENT_THEME_MISSING)).singleElement()
                .extracting(VariationFinding::field).isEqualTo("parent.variation_theme");
        // a missing theme is said once: the theme rule stays quiet about it
        assertThat(of(withoutTheme, RuleIds.VARIATION_THEME_ALLOWED)).isEmpty();
    }

    @Test
    void aParentWithoutChildrenIsAnInfoNotAWarning() {
        VariationResult result = check(withChildren(validFamily(), List.of()));

        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(RuleIds.NO_CHILDREN);
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
        });
    }

    // ---- variation_theme_allowed ---------------------------------------------------------------------------------

    @Test
    void aThemeOutsideTheListOfADeclaredProductTypeIsReported() {
        String stray = theme().name() + "-Material";
        assertThat(RULESET.theme(stray)).isEmpty();
        VariationInput.Parent parent = new VariationInput.Parent(PARENT_SKU, apparelType().productType(), stray, null);

        VariationResult result = check(withParent(validFamily(), parent));

        assertThat(ofKind(result, RuleIds.THEME_NOT_ALLOWED)).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo(RuleIds.VARIATION_THEME_ALLOWED);
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.field()).isEqualTo("parent.variation_theme");
            assertThat(finding.value()).isEqualTo(stray);
            assertThat(finding.message()).contains("'" + stray + "'", apparelType().productType())
                    .contains(apparelType().themes().toArray(new String[0]));
        });
        // an unknown theme has no attribute list: the children are not blamed for it, and an INFO says so
        assertThat(ofKind(result, RuleIds.CHILD_ATTRIBUTE_MISSING)).isEmpty();
        assertThat(ofKind(result, RuleIds.THEME_ATTRIBUTES_UNKNOWN)).singleElement()
                .extracting(VariationFinding::severity).isEqualTo(Severity.INFO);
    }

    @Test
    void theThemeIsMatchedIgnoringCase() {
        VariationInput.Parent parent = new VariationInput.Parent(PARENT_SKU, apparelType().productType().toLowerCase(),
                apparelType().themes().get(0).toUpperCase(), null);

        assertThat(check(withParent(validFamily(), parent)).findings()).isEmpty();
    }

    @Test
    void aProductTypeTheRulesetDoesNotKnowIsAnInfoAndNothingElse() {
        String unknown = "LUGGAGE";
        assertThat(RULESET.themeAllowed().find(unknown)).isEmpty();
        assertThat(RULESET.apparel().covers(unknown)).isFalse();
        VariationInput.Parent parent = new VariationInput.Parent(PARENT_SKU, unknown, "Color-Size", null);

        VariationResult result = check(withParent(validFamily(), parent));

        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo(RuleIds.VARIATION_THEME_ALLOWED);
            assertThat(finding.kind()).isEqualTo(RuleIds.PRODUCT_TYPE_NOT_COVERED);
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
            assertThat(finding.value()).isEqualTo(unknown);
            assertThat(finding.message()).contains("'" + unknown + "'");
        });
    }

    @Test
    void aMissingProductTypeIsAnInfo() {
        VariationInput.Parent parent = new VariationInput.Parent(PARENT_SKU, null, apparelType().themes().get(0), null);

        assertThat(check(withParent(validFamily(), parent)).findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(RuleIds.PRODUCT_TYPE_MISSING);
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
        });
    }

    // ---- duplicate_combination and duplicate_sku -----------------------------------------------------------------

    @Test
    void aRepeatedCombinationIsReportedOnTheLaterChildAndNamesTheFirst() {
        VariationInput family = validFamily();
        VariationInput.Child first = family.children().get(1);
        // the same color and size as child 1, spelled in another case, under another SKU
        Map<String, String> sameValues = new LinkedHashMap<>(first.attributes());
        sameValues.put(colorAttribute(), first.attributes().get(colorAttribute()).toUpperCase());
        VariationInput.Child twin = child("FAM-TWIN", PARENT_SKU, first.title(), sameValues);
        List<VariationInput.Child> children = new ArrayList<>(family.children());
        children.add(twin);

        List<VariationFinding> found = of(check(withChildren(family, children)), RuleIds.DUPLICATE_COMBINATION);

        assertThat(found).singleElement().satisfies(finding -> {
            assertThat(finding.field()).isEqualTo("children[" + (children.size() - 1) + "]");
            assertThat(finding.relatedFields()).containsExactly("children[1]");
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.sourceStatus()).isEqualTo(SourceStatus.UNVERIFIED);
            assertThat(finding.value()).isEqualTo(sameValues.get(colorAttribute()) + " / " + sameValues.get(sizeAttribute()));
            assertThat(finding.message()).contains("thứ " + children.size(), "thứ 2");
        });
    }

    @Test
    void aChildThatLacksAnAttributeHasNoCombinationToRepeat() {
        VariationInput family = validFamily();
        VariationInput.Child a = child("X1", PARENT_SKU, null, without(attributes("Red", "S"), sizeAttribute()));
        VariationInput.Child b = child("X2", PARENT_SKU, null, without(attributes("Red", "S"), sizeAttribute()));

        VariationResult result = check(withChildren(family, List.of(a, b)));

        assertThat(of(result, RuleIds.DUPLICATE_COMBINATION)).isEmpty();
    }

    @Test
    void aRepeatedSkuIsReportedAmongChildrenAndWithTheParent() {
        VariationInput family = validFamily();
        String reused = family.children().get(1).sku();
        VariationInput twice = replacing(family, 4, withSku(family.children().get(4), reused));
        VariationInput withParentSku = replacing(twice, 6, withSku(family.children().get(6), PARENT_SKU));

        VariationResult result = check(withParentSku);

        assertThat(ofKind(result, RuleIds.DUPLICATE_SKU_KIND)).singleElement().satisfies(finding -> {
            assertThat(finding.field()).isEqualTo("children[4].sku");
            assertThat(finding.relatedFields()).containsExactly("children[1].sku");
            assertThat(finding.value()).isEqualTo(reused);
            assertThat(finding.message()).contains("thứ 5", "thứ 2", "'" + reused + "'");
        });
        assertThat(ofKind(result, RuleIds.SKU_EQUALS_PARENT)).singleElement().satisfies(finding -> {
            assertThat(finding.field()).isEqualTo("children[6].sku");
            assertThat(finding.relatedFields()).containsExactly("parent.sku");
        });
    }

    @Test
    void skusAreComparedExactly() {
        VariationInput family = validFamily();
        String sku = family.children().get(1).sku();

        assertThat(of(check(replacing(family, 4, withSku(family.children().get(4), sku.toLowerCase()))),
                RuleIds.DUPLICATE_SKU)).isEmpty();
    }

    private static VariationInput.Child withSku(VariationInput.Child item, String sku) {
        return child(sku, item.parentSku(), item.title(), item.attributes());
    }

    // ---- children_differ_outside_theme ---------------------------------------------------------------------------

    @Test
    void twoTitlesThatDifferOutsideSizeAndColorAreReportedWithTheDifferingText() {
        VariationInput family = withChildren(validFamily(), List.of(
                child("A", PARENT_SKU, "Retro Sunset Tee - Red - M", attributes("Red", "M")),
                child("B", PARENT_SKU, "Mountain Hike Tee - Red - L", attributes("Red", "L"))));

        List<VariationFinding> found = of(check(family), RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME);

        assertThat(found).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.sourceStatus()).isEqualTo(SourceStatus.UNVERIFIED);
            assertThat(finding.field()).isEqualTo("children[1].title");
            assertThat(finding.value()).isEqualTo("mountain hike tee");
            assertThat(finding.relatedFields()).containsExactly("children[0].title");
            assertThat(finding.message()).contains("'mountain hike tee'", "'retro sunset tee'", "thứ 2", "thứ 1");
        });
    }

    @Test
    void theLargerGroupIsTheReferenceAndAlwaysTheOddOnesAreReported() {
        VariationInput.Child a = child("A", PARENT_SKU, "Retro Sunset Tee - Red - S", attributes("Red", "S"));
        VariationInput.Child b = child("B", PARENT_SKU, "Mountain Hike Tee - Red - M", attributes("Red", "M"));
        VariationInput.Child c = child("C", PARENT_SKU, "Mountain Hike Tee - Red - L", attributes("Red", "L"));
        VariationInput base = validFamily();

        // one odd child at the end
        List<VariationFinding> oddLast = of(check(withChildren(base, List.of(b, c, a))), RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME);
        assertThat(oddLast).singleElement().extracting(VariationFinding::field).isEqualTo("children[2].title");
        // one odd child first: the later, larger group is the reference
        List<VariationFinding> oddFirst = of(check(withChildren(base, List.of(a, b, c))), RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME);
        assertThat(oddFirst).singleElement().satisfies(finding -> {
            assertThat(finding.field()).isEqualTo("children[0].title");
            assertThat(finding.relatedFields()).containsExactly("children[1].title");
        });
    }

    @Test
    void sizeAndColorWordsAndTheChildsOwnValuesAreNotADifference() {
        VariationInput.Child a = child("A", PARENT_SKU, "Retro Sunset Tee - Heather Grey - XL",
                attributes("Heather Grey", "XL"));
        VariationInput.Child b = child("B", PARENT_SKU, "Retro Sunset Tee, Navy, Small", attributes("Navy", "Small"));
        // a color that only this child's attributes say
        VariationInput.Child c = child("C", PARENT_SKU, "Retro Sunset Tee Sandstone M", attributes("Sandstone", "M"));

        assertThat(of(check(withChildren(validFamily(), List.of(a, b, c))), RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME)).isEmpty();
    }

    @Test
    void childrenWithoutTitlesAreNotCompared() {
        VariationInput.Child a = child("A", PARENT_SKU, "Retro Sunset Tee - Red - M", attributes("Red", "M"));
        VariationInput.Child b = child("B", PARENT_SKU, null, attributes("Red", "L"));

        assertThat(of(check(withChildren(validFamily(), List.of(a, b))), RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME)).isEmpty();
        assertThat(of(check(withChildren(validFamily(), List.of(a))), RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME)).isEmpty();
    }

    // ---- apparel_size_attributes ---------------------------------------------------------------------------------

    /** A family of one child with these attributes, so that nothing else in the family can add a finding. */
    private static VariationResult withAttributes(Map<String, String> attributes) {
        return check(withChildren(validFamily(), List.of(child("ONLY", PARENT_SKU, "Retro Sunset Tee", attributes))));
    }

    @Test
    void everyRequiredApparelAttributeMustBeFilled() {
        for (String required : RULESET.apparel().requiredAttributes()) {
            VariationResult result = withAttributes(without(attributes("Red", "S"), required));
            assertThat(ofKind(result, RuleIds.SIZE_ATTRIBUTE_MISSING))
                    .as("a child without %s", required)
                    .extracting(VariationFinding::field).contains("children[0].attributes." + required);
        }
    }

    @Test
    void aValueOutsideAClosedListIsReportedWithTheListInTheMessage() {
        for (Map.Entry<String, List<String>> list : RULESET.apparel().valueLists().entrySet()) {
            String invalid = list.getValue().get(0) + "-nope";
            VariationResult result = withAttributes(with(attributes("Red", "S"), list.getKey(), invalid));

            assertThat(ofKind(result, RuleIds.SIZE_ATTRIBUTE_INVALID)).as("%s = %s", list.getKey(), invalid)
                    .singleElement().satisfies(finding -> {
                        assertThat(finding.field()).isEqualTo("children[0].attributes." + list.getKey());
                        assertThat(finding.value()).isEqualTo(invalid);
                        assertThat(finding.message()).contains(String.join(", ", list.getValue()));
                    });
        }
    }

    @Test
    void aValueOfAClosedListIsMatchedIgnoringCase() {
        String list = RANGE.classAttribute();
        String shouting = RULESET.apparel().valueLists().get(list).get(0).toUpperCase();

        assertThat(ofKind(withAttributes(with(attributes("Red", "S"), list, shouting)), RuleIds.SIZE_ATTRIBUTE_INVALID))
                .isEmpty();
    }

    @Test
    void anAlphaRangeNeedsSizeToLargerThanSize() {
        String alphaRange = rangeClass(VariationRuleset.RangeKind.ALPHA);
        Map<String, String> base = with(attributes("Red", "M"), RANGE.classAttribute(), alphaRange);

        VariationResult missing = withAttributes(base);
        assertThat(ofKind(missing, RuleIds.SIZE_TO_MISSING)).singleElement()
                .extracting(VariationFinding::field).isEqualTo("children[0].attributes." + RANGE.sizeToAttribute());

        VariationResult smaller = withAttributes(with(base, RANGE.sizeToAttribute(), "S"));
        assertThat(ofKind(smaller, RuleIds.SIZE_TO_NOT_GREATER)).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.value()).isEqualTo("S");
            assertThat(finding.relatedFields()).containsExactly("children[0].attributes." + RANGE.sizeAttribute());
            assertThat(finding.message()).contains("'S'", "'M'");
        });

        VariationResult equal = withAttributes(with(base, RANGE.sizeToAttribute(), "m"));
        assertThat(ofKind(equal, RuleIds.SIZE_TO_NOT_GREATER)).hasSize(1);

        VariationResult larger = withAttributes(with(base, RANGE.sizeToAttribute(), "XL"));
        assertThat(larger.findings()).isEmpty();
    }

    @Test
    void labelsOfOneRankAreEqualAndTheScaleDecidesTheOrder() {
        String alphaRange = rangeClass(VariationRuleset.RangeKind.ALPHA);
        // the largest two ranks of the scale, whatever they are
        List<List<String>> order = RULESET.apparel().alphaScale().order();
        String low = order.get(order.size() - 2).get(0);
        String high = order.get(order.size() - 1).get(0);
        Map<String, String> base = with(attributes("Red", low), RANGE.classAttribute(), alphaRange);

        assertThat(withAttributes(with(base, RANGE.sizeToAttribute(), high)).findings()).isEmpty();
        assertThat(ofKind(withAttributes(with(with(base, RANGE.sizeAttribute(), high), RANGE.sizeToAttribute(), low)),
                RuleIds.SIZE_TO_NOT_GREATER)).hasSize(1);
        List<String> sameRank = order.stream().filter(rank -> rank.size() > 1).findFirst().orElseThrow();
        assertThat(ofKind(withAttributes(with(with(base, RANGE.sizeAttribute(), sameRank.get(0)),
                RANGE.sizeToAttribute(), sameRank.get(1))), RuleIds.SIZE_TO_NOT_GREATER)).hasSize(1);
    }

    @Test
    void aNumericRangeIsComparedAsNumbers() {
        String numericRange = rangeClass(VariationRuleset.RangeKind.NUMERIC);
        Map<String, String> base = with(attributes("Red", "10"), RANGE.classAttribute(), numericRange);

        // as text "12" < "8", as numbers 12 > 8
        assertThat(withAttributes(with(with(base, RANGE.sizeAttribute(), "8"), RANGE.sizeToAttribute(), "12")).findings())
                .isEmpty();
        assertThat(withAttributes(with(base, RANGE.sizeToAttribute(), "12.5")).findings()).isEmpty();
        assertThat(ofKind(withAttributes(with(base, RANGE.sizeToAttribute(), "8")), RuleIds.SIZE_TO_NOT_GREATER)).hasSize(1);
        assertThat(ofKind(withAttributes(with(base, RANGE.sizeToAttribute(), "10")), RuleIds.SIZE_TO_NOT_GREATER)).hasSize(1);
    }

    @Test
    void aRangeThatCannotBeComparedIsAnInfoNeverAWarning() {
        String numericRange = rangeClass(VariationRuleset.RangeKind.NUMERIC);
        String alphaRange = rangeClass(VariationRuleset.RangeKind.ALPHA);

        VariationResult notANumber = withAttributes(with(with(attributes("Red", "ten"), RANGE.classAttribute(), numericRange),
                RANGE.sizeToAttribute(), "12"));
        VariationResult notInTheScale = withAttributes(with(with(attributes("Red", "M"), RANGE.classAttribute(), alphaRange),
                RANGE.sizeToAttribute(), "no-such-size"));

        for (VariationResult result : List.of(notANumber, notInTheScale)) {
            assertThat(result.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.kind()).isEqualTo(RuleIds.SIZE_RANGE_NOT_COMPARABLE);
                assertThat(finding.severity()).isEqualTo(Severity.INFO);
            });
        }
    }

    @Test
    void aRangeOfAnUnorderedClassNeedsSizeToButIsNotCompared() {
        String ageRange = rangeClass(VariationRuleset.RangeKind.NONE);
        Map<String, String> base = with(attributes("Red", "6"), RANGE.classAttribute(), ageRange);

        assertThat(ofKind(withAttributes(base), RuleIds.SIZE_TO_MISSING)).hasSize(1);
        assertThat(withAttributes(with(base, RANGE.sizeToAttribute(), "2")).findings()).isEmpty();
    }

    @Test
    void aClassThatIsNotARangeNeedsNoSizeTo() {
        assertThat(withAttributes(attributes("Red", "M")).findings()).isEmpty();
    }

    // ---- etsy_variation_limits -----------------------------------------------------------------------------------

    private static List<String> properties(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            names.add("Type " + i);
        }
        return names;
    }

    @Test
    void moreVariationTypesThanTheLimitIsReported() {
        int limit = RULESET.etsyLimits().maxVariationTypes();

        assertThat(of(check(etsy(properties(limit), 2)), RuleIds.ETSY_VARIATION_LIMITS)).isEmpty();
        List<VariationFinding> found = ofKind(check(etsy(properties(limit + 1), 2)), RuleIds.TOO_MANY_VARIATION_TYPES);

        assertThat(found).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.sourceStatus()).isEqualTo(SourceStatus.SECONDARY);
            assertThat(finding.field()).isEqualTo("variation_properties");
            assertThat(finding.value()).isEqualTo(String.valueOf(limit + 1));
            assertThat(finding.message()).contains(String.valueOf(limit + 1), String.valueOf(limit), "Type 1");
        });
    }

    @Test
    void aRepeatedVariationTypeCountsOnce() {
        int limit = RULESET.etsyLimits().maxVariationTypes();
        List<String> names = new ArrayList<>(properties(limit));
        names.add(names.get(0).toUpperCase());

        assertThat(ofKind(check(etsy(names, 2)), RuleIds.TOO_MANY_VARIATION_TYPES)).isEmpty();
    }

    @Test
    void aPropertyThatCannotBeAVariationIsReportedIgnoringCase() {
        String unsupported = RULESET.etsyLimits().unsupportedProperties().get(0);
        VariationResult result = check(etsy(List.of("Size", unsupported.toUpperCase()), 2));

        assertThat(ofKind(result, RuleIds.VARIATION_TYPE_NOT_SUPPORTED)).singleElement().satisfies(finding -> {
            assertThat(finding.field()).isEqualTo("variation_properties[1]");
            assertThat(finding.value()).isEqualTo(unsupported.toUpperCase());
            assertThat(finding.message()).contains(RULESET.etsyLimits().unsupportedProperties().toArray(new String[0]));
        });
    }

    @Test
    void moreCombinationsThanCanHaveAPhotoIsAnInfoAtTheLimitItIsNothing() {
        int limit = RULESET.etsyLimits().photoVariationLimit();

        assertThat(check(etsy(List.of("Size"), limit)).findings()).isEmpty();
        VariationResult over = check(etsy(List.of("Size"), limit + 1));

        assertThat(over.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(RuleIds.MANY_VARIATIONS_PHOTO_LIMIT);
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
            assertThat(finding.value()).isEqualTo(String.valueOf(limit + 1));
            assertThat(finding.message()).contains(String.valueOf(limit + 1), String.valueOf(limit));
        });
    }

    @Test
    void etsyChecksTheCombinationAndSkuRulesToo() {
        VariationInput.Child a = child("S1", null, null, Map.of("Size", "M"));
        VariationInput.Child b = child("S1", null, null, Map.of("Size", "m"));
        VariationInput input = new VariationInput("ETSY", null, List.of("Size"), List.of(a, b));

        VariationResult result = check(input);

        assertThat(result.findings()).extracting(VariationFinding::kind)
                .containsExactly(RuleIds.DUPLICATE_COMBINATION, RuleIds.DUPLICATE_SKU_KIND);
    }

    @Test
    void anEtsyListingIsNeverAskedForAParentOrAmazonAttributes() {
        VariationResult result = check(etsy(List.of("Size"), 3));

        assertThat(result.findings()).isEmpty();
    }

    @Test
    void anAmazonFamilyIsNeverTheEtsyLimits() {
        VariationInput family = validFamily();

        assertThat(of(check(family), RuleIds.ETSY_VARIATION_LIMITS)).isEmpty();
        assertThat(family.children().size()).isGreaterThan(RULESET.etsyLimits().photoVariationLimit());
    }

    // ---- bounds and severity -------------------------------------------------------------------------------------

    @Test
    void clientTextIsCutToTheRulesetLimitInTheValueAndInTheMessage() {
        String longTheme = "T".repeat(RULESET.maxValueChars() * 3);
        VariationInput.Parent parent = new VariationInput.Parent(PARENT_SKU, apparelType().productType(), longTheme, null);

        VariationResult result = check(withParent(validFamily(), parent));

        for (VariationFinding finding : result.findings()) {
            assertThat(finding.value() == null ? 0 : finding.value().length()).isLessThanOrEqualTo(RULESET.maxValueChars());
            assertThat(finding.message()).doesNotContain(longTheme);
        }
        assertThat(ofKind(result, RuleIds.THEME_NOT_ALLOWED).get(0).value()).hasSize(RULESET.maxValueChars());
    }

    @Test
    void noFindingIsEverAnErrorWhileNothingIsVerified() {
        List<VariationInput> inputs = List.of(validFamily(), withParent(validFamily(), null),
                withChildren(validFamily(), List.of()), etsy(properties(5), 20));
        for (VariationInput input : inputs) {
            assertThat(check(input).findings()).extracting(VariationFinding::severity).doesNotContain(Severity.ERROR);
        }
    }

    @Test
    void theFindingsFollowTheOrderOfTheRulesThenTheChildren() {
        VariationInput family = validFamily();
        VariationInput.Child first = family.children().get(0);
        VariationInput.Child second = family.children().get(1);
        VariationInput broken = replacing(replacing(family, 0,
                child(first.sku(), "X", first.title(), first.attributes())), 1,
                child(second.sku(), "Y", second.title(), second.attributes()));

        assertThat(check(broken).findings()).extracting(VariationFinding::field)
                .containsExactly("children[0].parent_sku", "children[1].parent_sku");
    }
}
