package com.thanhdatpb.listingguard.api.variation;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.variation.VariationInput;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps the request to the family the rules read (docs/input-schema.md, "Variation checks"). Like the listing normalizers
 * it only maps and cleans (trim, drop blanks) and never dedupes, truncates, corrects or reorders, because the rules must see
 * what the seller wrote:
 *
 * <ul>
 *   <li>a string is trimmed, and blank is absent; a number or boolean is read as its text; an object or array where text is
 *       expected is absent; a value that is not an array where an array is expected is an empty list;
 *   <li>an attribute name is trimmed and an attribute whose name or value is blank is dropped; if two names are equal once
 *       trimmed the first is kept;
 *   <li>a child that is not an object, or has no sku, parent sku, title or attribute, is dropped, so {@code children[i]}
 *       counts the cleaned list; a parent with no field is absent;
 *   <li>Amazon reads {@code parent} and {@code parent_sku}; Etsy reads {@code variation_properties}. The other marketplace's
 *       field is ignored.
 * </ul>
 */
@Component
public class VariationNormalizer {

    public VariationInput normalize(String marketplace, VariationCheckRequest request) {
        boolean amazon = VariationRuleset.AMAZON.equals(marketplace);
        VariationInput.Parent parent = amazon ? parent(request.parent()) : null;
        List<String> properties = amazon ? List.of() : strings(request.variationProperties());
        return new VariationInput(marketplace, parent, properties, children(request.children(), amazon));
    }

    private static VariationInput.Parent parent(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String sku = text(node.get("sku"));
        String productType = text(node.get("product_type"));
        String theme = text(node.get("variation_theme"));
        String title = text(node.get("title"));
        if (sku == null && productType == null && theme == null && title == null) {
            return null;
        }
        return new VariationInput.Parent(sku, productType, theme, title);
    }

    private static List<VariationInput.Child> children(JsonNode node, boolean amazon) {
        List<VariationInput.Child> children = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return children;
        }
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            String sku = text(item.get("sku"));
            String parentSku = amazon ? text(item.get("parent_sku")) : null;
            String title = text(item.get("title"));
            Map<String, String> attributes = attributes(item.get("attributes"));
            if (sku != null || parentSku != null || title != null || !attributes.isEmpty()) {
                children.add(new VariationInput.Child(sku, parentSku, title, attributes));
            }
        }
        return children;
    }

    private static Map<String, String> attributes(JsonNode node) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return attributes;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey().trim();
            String value = text(entry.getValue());
            if (!name.isEmpty() && value != null) {
                attributes.putIfAbsent(name, value);
            }
        }
        return attributes;
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            String value = text(item);
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    /** A trimmed string value, or null when absent, null, not a plain value or blank. */
    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }
}
