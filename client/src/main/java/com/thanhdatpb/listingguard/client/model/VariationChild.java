package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One child of a variation family. Every field is optional.
 *
 * @param sku        the child's SKU
 * @param parentSku  Amazon only: the parent's SKU (compared exactly, case matters)
 * @param title      the child's title
 * @param attributes attribute name to value, for example {@code color} to {@code Red}; the order is kept
 */
public record VariationChild(
        String sku,
        @JsonProperty("parent_sku") String parentSku,
        String title,
        Map<String, String> attributes) {

    public VariationChild {
        attributes = attributes == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** A child with a SKU and attributes only (the usual Etsy child). */
    public static VariationChild of(String sku, Map<String, String> attributes) {
        return new VariationChild(sku, null, null, attributes);
    }
}
