package com.thanhdatpb.listingguard.variation;

import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import com.thanhdatpb.listingguard.variation.config.VariationRulesetLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic variation families for the tests. Everything that comes from the ruleset (the apparel product type, its
 * theme and the attribute names, the closed value lists, the size order, the limits) is read from it, never repeated.
 */
final class VariationFixtures {

    static final String RULESET_PATH = "rulesets/variation-checks-2026-11.yaml";
    static final VariationRuleset RULESET = VariationRulesetLoader.loadClasspath(RULESET_PATH);
    static final VariationChecker CHECKER = new VariationChecker(RULESET);

    static final String PARENT_SKU = "FAM-P";
    static final List<String> COLORS = List.of("Red", "Blue");

    private VariationFixtures() {
    }

    /** A product type that the ruleset lists both for themes and as apparel. */
    static VariationRuleset.ProductTypeThemes apparelType() {
        return RULESET.themeAllowed().productTypes().values().stream()
                .filter(entry -> RULESET.apparel().covers(entry.productType())).findFirst().orElseThrow();
    }

    /** The first theme the apparel type allows, with the attributes a child must fill (color first, size second). */
    static VariationRuleset.Theme theme() {
        return RULESET.theme(apparelType().themes().get(0)).orElseThrow();
    }

    static String colorAttribute() {
        return theme().attributes().get(0);
    }

    static String sizeAttribute() {
        return theme().attributes().get(1);
    }

    /** Alpha sizes from S to the largest of the scale, spelled in upper case: the first label of each rank from the third. */
    static List<String> sizes() {
        List<List<String>> order = RULESET.apparel().alphaScale().order();
        List<String> sizes = new ArrayList<>();
        for (int rank = 2; rank < order.size(); rank++) {
            sizes.add(order.get(rank).get(0).toUpperCase());
        }
        return sizes;
    }

    /** A size class value that is not a range. */
    static String plainClass() {
        VariationRuleset.Range range = RULESET.apparel().range();
        return RULESET.apparel().valueLists().get(range.classAttribute()).stream()
                .filter(value -> !range.classes().containsKey(value.toLowerCase())).findFirst().orElseThrow();
    }

    /** A size class value that is a range read as {@code kind}. */
    static String rangeClass(VariationRuleset.RangeKind kind) {
        return RULESET.apparel().range().classes().entrySet().stream().filter(entry -> entry.getValue() == kind)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    /** All the attributes of a valid apparel child. */
    static Map<String, String> attributes(String color, String size) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(colorAttribute(), color);
        attributes.put(sizeAttribute(), size);
        attributes.put(RULESET.apparel().range().sizeAttribute(), size);
        for (String required : RULESET.apparel().requiredAttributes()) {
            if (attributes.containsKey(required)) {
                continue;
            }
            List<String> allowed = RULESET.apparel().valueLists().get(required);
            attributes.put(required, required.equals(RULESET.apparel().range().classAttribute()) ? plainClass() : allowed.get(0));
        }
        return attributes;
    }

    static VariationInput.Child child(String sku, String parentSku, String title, Map<String, String> attributes) {
        return new VariationInput.Child(sku, parentSku, title, attributes);
    }

    static VariationInput.Parent parent() {
        return new VariationInput.Parent(PARENT_SKU, apparelType().productType(), apparelType().themes().get(0),
                "Retro Sunset Tee");
    }

    /** One parent and a child for every color and size: a family that the pack has nothing to say about. */
    static VariationInput validFamily() {
        List<VariationInput.Child> children = new ArrayList<>();
        for (String color : COLORS) {
            for (String size : sizes()) {
                children.add(child("FAM-" + color + "-" + size, PARENT_SKU, "Retro Sunset Tee - " + color + " - " + size,
                        attributes(color, size)));
            }
        }
        return new VariationInput(VariationRuleset.AMAZON, parent(), List.of(), children);
    }

    static VariationInput withChildren(VariationInput input, List<VariationInput.Child> children) {
        return new VariationInput(input.marketplace(), input.parent(), input.variationProperties(), children);
    }

    static VariationInput withParent(VariationInput input, VariationInput.Parent parent) {
        return new VariationInput(input.marketplace(), parent, input.variationProperties(), input.children());
    }

    /** The family with child {@code index} replaced. */
    static VariationInput replacing(VariationInput input, int index, VariationInput.Child replacement) {
        List<VariationInput.Child> children = new ArrayList<>(input.children());
        children.set(index, replacement);
        return withChildren(input, children);
    }

    /** The attributes of the child without one of them. */
    static Map<String, String> without(Map<String, String> attributes, String name) {
        Map<String, String> copy = new LinkedHashMap<>(attributes);
        copy.remove(name);
        return copy;
    }

    static Map<String, String> with(Map<String, String> attributes, String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(attributes);
        copy.put(name, value);
        return copy;
    }

    static List<VariationFinding> of(VariationResult result, String ruleId) {
        return result.findings().stream().filter(finding -> finding.ruleId().equals(ruleId)).toList();
    }

    static List<VariationFinding> ofKind(VariationResult result, String kind) {
        return result.findings().stream().filter(finding -> finding.kind().equals(kind)).toList();
    }

    /** An Etsy family: the properties and a child for every number from 0 to {@code children}, each with its own values. */
    static VariationInput etsy(List<String> properties, int children) {
        List<VariationInput.Child> list = new ArrayList<>();
        for (int i = 0; i < children; i++) {
            Map<String, String> attributes = new LinkedHashMap<>();
            for (String property : properties) {
                attributes.put(property, property + " " + i);
            }
            list.add(child("ETSY-" + i, null, null, attributes));
        }
        return new VariationInput(VariationRuleset.ETSY, null, properties, list);
    }
}
