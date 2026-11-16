package com.thanhdatpb.listingguard.api.edit;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Request for a side-by-side edit comparison. Listing values use the existing marketplace input schema. */
public record ListingEditRequest(
        @NotBlank String marketplace,
        @NotNull @Schema(type = "object") JsonNode before,
        @NotNull @Schema(type = "object") JsonNode after,
        @com.fasterxml.jackson.annotation.JsonProperty("before_variations") VariationFamily beforeVariations,
        @com.fasterxml.jackson.annotation.JsonProperty("after_variations") VariationFamily afterVariations) {

    @AssertTrue(message = "before and after must be objects")
    public boolean listingBodiesAreObjects() {
        return before != null && before.isObject() && after != null && after.isObject();
    }

    public record VariationFamily(
            @Schema(type = "object", nullable = true) JsonNode parent,
            @com.fasterxml.jackson.annotation.JsonProperty("variation_properties")
            @Schema(type = "array", nullable = true) List<String> variationProperties,
            @Schema(type = "array", nullable = true) JsonNode children) {
    }
}
