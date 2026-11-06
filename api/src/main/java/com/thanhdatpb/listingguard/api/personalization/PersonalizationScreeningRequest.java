package com.thanhdatpb.listingguard.api.personalization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * The request of {@code POST /api/v1/personalization-screenings} (docs/input-schema.md, "Personalization screening"). Not
 * wrapped in {@code listing}: the text a buyer typed is not a listing, and the {@code /checks} input is untouched. Every field
 * but {@code marketplace} is read leniently by {@link PersonalizationNormalizer}: a missing or unreadable one is absent, not
 * an error. The one exception is a {@code print_profile} the ruleset does not declare, which is a 400.
 */
public record PersonalizationScreeningRequest(
        @Schema(description = "Marketplace code, case-insensitive. Supported: ETSY, AMAZON.", example = "ETSY")
        @NotBlank String marketplace,
        @Schema(description = "Free text naming the product, for example \"jersey\". Hashed and not read by any rule yet.",
                type = "string", nullable = true, example = "jersey")
        @JsonProperty("product_type") JsonNode productType,
        @Schema(description = "The code of a print profile declared in the ruleset. Absent means the ruleset's default profile. "
                + "An undeclared code is a 400 (`unknown-print-profile`), never replaced by the default.",
                type = "string", nullable = true, example = "latin_basic")
        @JsonProperty("print_profile") JsonNode printProfile,
        @Schema(description = "What the buyer typed, one entry per field of the order: objects with `label` (optional, the name of "
                + "the field, for example \"name\") and `text`. The text is NOT trimmed: spaces are reported, not removed. Entries "
                + "that are not objects or have no text are dropped.", type = "array", nullable = true)
        JsonNode values) {
}
