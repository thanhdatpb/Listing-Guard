package com.thanhdatpb.listingguard.api.check;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CheckRequest(
        @Schema(description = "Marketplace code, case-insensitive. Supported: ETSY, AMAZON.", example = "ETSY")
        @NotBlank String marketplace,
        @Schema(description = "The listing in the marketplace's own JSON format (for Etsy, Open API v3 style snake_case fields; for Amazon, "
                + "title, brand, bullet_points, description, search_terms, product_type, price, quantity, images). "
                + "Must be a JSON object.", types = "object")
        @NotNull JsonNode listing) {
}
