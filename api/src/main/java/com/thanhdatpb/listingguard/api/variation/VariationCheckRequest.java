package com.thanhdatpb.listingguard.api.variation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * The request of {@code POST /api/v1/variation-checks} (docs/input-schema.md, "Variation checks"). Not wrapped in
 * {@code listing}: a variation family is not a listing, and the {@code /checks} input is untouched. Every field but
 * {@code marketplace} is read leniently by {@link VariationNormalizer}: a missing or unreadable one is absent, not an
 * error, and the rules say what is missing.
 */
public record VariationCheckRequest(
        @Schema(description = "Marketplace code, case-insensitive. Supported: ETSY, AMAZON.", example = "AMAZON")
        @NotBlank String marketplace,
        @Schema(description = "Amazon: the parent, an object with sku, product_type, variation_theme and title (all optional). "
                + "Ignored for Etsy, where the listing is its own parent.", type = "object", nullable = true)
        JsonNode parent,
        @Schema(description = "Etsy: the names of the variation types, for example [\"Primary color\", \"Size\"]. Ignored for Amazon.",
                type = "array", nullable = true)
        @JsonProperty("variation_properties") JsonNode variationProperties,
        @Schema(description = "The children: objects with sku, parent_sku (Amazon only), title and attributes, a map from an attribute "
                + "name to its value. Entries that are not objects or are empty are dropped.", type = "array", nullable = true)
        JsonNode children) {
}
