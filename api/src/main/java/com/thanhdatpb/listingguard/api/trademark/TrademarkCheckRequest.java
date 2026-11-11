package com.thanhdatpb.listingguard.api.trademark;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The request of {@code POST /api/v1/trademark-checks} (ADR-009): the envelope of {@code POST /api/v1/checks}
 * ({@code marketplace} and {@code listing}) plus an optional {@code product_type}. {@code /checks} ignores a
 * {@code product_type} it is sent, so one JSON can still go to both endpoints.
 */
public record TrademarkCheckRequest(
        @Schema(description = "Marketplace code, case-insensitive. Supported: ETSY, AMAZON.", example = "ETSY")
        @NotBlank String marketplace,
        @Schema(description = "The listing in the marketplace's own JSON format, exactly as for POST /api/v1/checks. Must be a JSON object.",
                types = "object")
        @NotNull JsonNode listing,
        @JsonProperty("product_type")
        @Schema(description = "Optional. What kind of product the listing sells: one of the product types the active trademark ruleset "
                + "declares (see the integration guide), written exactly as declared. When it is given, a catalog match whose mark has "
                + "Nice classes, none of them one of the product type's classes, is reported one level lower and the finding says so "
                + "in `riskAdjustments`; a mark with no Nice class is never lowered. A value the ruleset does not declare is "
                + "refused with 400 `unknown-product-type`, never ignored. Leave it out to get the result without product classes. "
                + "It is not the Amazon `product_type` of the listing, and it is part of what makes two requests identical for the cache.",
                example = "apparel", nullable = true)
        @Size(max = 64) String productType) {
}
