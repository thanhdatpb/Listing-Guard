package com.thanhdatpb.listingguard.variation.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of the pack, the finding kinds each one can report and the placeholders a message of each kind may use
 * (ADR-015). The ruleset must declare every rule once and every kind of each rule once; the loader checks the
 * placeholders of every message and suggestion against this table. Which marketplaces a rule can handle is logic and lives
 * here; which of them it runs for is the ruleset's {@code marketplaces}.
 */
public final class RuleIds {

    public static final String PARENT_CHILD_STRUCTURE = "parent_child_structure";
    public static final String VARIATION_THEME_ALLOWED = "variation_theme_allowed";
    public static final String APPAREL_SIZE_ATTRIBUTES = "apparel_size_attributes";
    public static final String DUPLICATE_COMBINATION = "duplicate_combination";
    public static final String DUPLICATE_SKU = "duplicate_sku";
    public static final String CHILDREN_DIFFER_OUTSIDE_THEME = "children_differ_outside_theme";
    public static final String ETSY_VARIATION_LIMITS = "etsy_variation_limits";

    public static final List<String> ALL = List.of(PARENT_CHILD_STRUCTURE, VARIATION_THEME_ALLOWED,
            APPAREL_SIZE_ATTRIBUTES, DUPLICATE_COMBINATION, DUPLICATE_SKU, CHILDREN_DIFFER_OUTSIDE_THEME,
            ETSY_VARIATION_LIMITS);

    // finding kinds
    public static final String PARENT_MISSING = "parent_missing";
    public static final String PARENT_THEME_MISSING = "parent_theme_missing";
    public static final String NO_CHILDREN = "no_children";
    public static final String THEME_ATTRIBUTES_UNKNOWN = "theme_attributes_unknown";
    public static final String CHILD_PARENT_SKU_MISSING = "child_parent_sku_missing";
    public static final String CHILD_PARENT_SKU_MISMATCH = "child_parent_sku_mismatch";
    public static final String CHILD_ATTRIBUTE_MISSING = "child_attribute_missing";
    public static final String THEME_NOT_ALLOWED = "theme_not_allowed";
    public static final String PRODUCT_TYPE_NOT_COVERED = "product_type_not_covered";
    public static final String PRODUCT_TYPE_MISSING = "product_type_missing";
    public static final String SIZE_ATTRIBUTE_MISSING = "size_attribute_missing";
    public static final String SIZE_ATTRIBUTE_INVALID = "size_attribute_invalid";
    public static final String SIZE_TO_MISSING = "size_to_missing";
    public static final String SIZE_TO_NOT_GREATER = "size_to_not_greater";
    public static final String SIZE_RANGE_NOT_COMPARABLE = "size_range_not_comparable";
    public static final String DUPLICATE_SKU_KIND = "duplicate_sku";
    public static final String SKU_EQUALS_PARENT = "sku_equals_parent";
    public static final String TOO_MANY_VARIATION_TYPES = "too_many_variation_types";
    public static final String VARIATION_TYPE_NOT_SUPPORTED = "variation_type_not_supported";
    public static final String MANY_VARIATIONS_PHOTO_LIMIT = "many_variations_photo_limit";

    /** The marketplaces each rule's code can handle. */
    static final Map<String, Set<String>> SUPPORTED_MARKETPLACES = Map.of(
            PARENT_CHILD_STRUCTURE, Set.of("AMAZON"),
            VARIATION_THEME_ALLOWED, Set.of("AMAZON"),
            APPAREL_SIZE_ATTRIBUTES, Set.of("AMAZON"),
            DUPLICATE_COMBINATION, Set.of("AMAZON", "ETSY"),
            DUPLICATE_SKU, Set.of("AMAZON", "ETSY"),
            CHILDREN_DIFFER_OUTSIDE_THEME, Set.of("AMAZON", "ETSY"),
            ETSY_VARIATION_LIMITS, Set.of("ETSY"));

    /** Rule id, then kind, then the placeholders a message and a suggestion of that kind may use. */
    static final Map<String, Map<String, Set<String>>> KINDS = kinds();

    private RuleIds() {
    }

    private static Map<String, Map<String, Set<String>>> kinds() {
        Map<String, Map<String, Set<String>>> kinds = new LinkedHashMap<>();
        kinds.put(PARENT_CHILD_STRUCTURE, Map.of(
                PARENT_MISSING, Set.of(),
                PARENT_THEME_MISSING, Set.of(),
                NO_CHILDREN, Set.of(),
                THEME_ATTRIBUTES_UNKNOWN, Set.of("theme"),
                CHILD_PARENT_SKU_MISSING, Set.of("position", "parent_sku"),
                CHILD_PARENT_SKU_MISMATCH, Set.of("position", "parent_sku", "actual"),
                CHILD_ATTRIBUTE_MISSING, Set.of("position", "theme", "attribute")));
        kinds.put(VARIATION_THEME_ALLOWED, Map.of(
                THEME_NOT_ALLOWED, Set.of("theme", "product_type", "allowed"),
                PRODUCT_TYPE_NOT_COVERED, Set.of("product_type"),
                PRODUCT_TYPE_MISSING, Set.of()));
        kinds.put(APPAREL_SIZE_ATTRIBUTES, Map.of(
                SIZE_ATTRIBUTE_MISSING, Set.of("position", "attribute", "product_type"),
                SIZE_ATTRIBUTE_INVALID, Set.of("position", "attribute", "value", "allowed"),
                SIZE_TO_MISSING, Set.of("position", "attribute", "size_class"),
                SIZE_TO_NOT_GREATER, Set.of("position", "attribute", "size", "size_to"),
                SIZE_RANGE_NOT_COMPARABLE, Set.of("position", "size", "size_to", "size_class")));
        kinds.put(DUPLICATE_COMBINATION, Map.of(
                DUPLICATE_COMBINATION, Set.of("position", "first_position", "combination")));
        kinds.put(DUPLICATE_SKU, Map.of(
                DUPLICATE_SKU_KIND, Set.of("position", "first_position", "sku"),
                SKU_EQUALS_PARENT, Set.of("position", "sku")));
        kinds.put(CHILDREN_DIFFER_OUTSIDE_THEME, Map.of(
                CHILDREN_DIFFER_OUTSIDE_THEME,
                Set.of("position", "remainder", "reference_position", "reference_remainder")));
        kinds.put(ETSY_VARIATION_LIMITS, Map.of(
                TOO_MANY_VARIATION_TYPES, Set.of("count", "limit", "properties"),
                VARIATION_TYPE_NOT_SUPPORTED, Set.of("property", "unsupported"),
                MANY_VARIATIONS_PHOTO_LIMIT, Set.of("count", "limit")));
        return kinds;
    }
}
