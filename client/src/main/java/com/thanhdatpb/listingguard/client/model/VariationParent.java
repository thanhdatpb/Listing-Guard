package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The parent of an Amazon variation family. Every field is optional: a missing one is reported by a rule, not refused.
 *
 * @param sku            the SKU the children point at
 * @param productType    for example {@code SHIRT}
 * @param variationTheme for example {@code Color-Size}
 * @param title          the parent's title
 */
public record VariationParent(
        String sku,
        @JsonProperty("product_type") String productType,
        @JsonProperty("variation_theme") String variationTheme,
        String title) {
}
