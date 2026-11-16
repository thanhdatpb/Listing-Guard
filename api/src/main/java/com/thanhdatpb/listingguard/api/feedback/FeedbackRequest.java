package com.thanhdatpb.listingguard.api.feedback;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * The body of {@code POST /api/v1/feedback} (ADR-034): one verdict on one finding of a stored result of the calling client.
 */
public record FeedbackRequest(
        @Schema(description = "The pack of the result: `listing-rules`, `trademark-risk` or `variation-checks`.", example = "listing-rules")
        @NotBlank String pack,
        @Schema(description = "The `checkId` of the stored result, as POST or GET of that pack returned it.",
                example = "0f9e7c1a-2b3c-4d5e-8f90-1a2b3c4d5e6f")
        @NotNull UUID checkId,
        @Schema(description = "The index of the finding in that result's `findings`, from 0, in the order GET returns them.", example = "0")
        @NotNull @Min(0) Integer findingIndex,
        @Schema(description = "`CORRECT`, `FALSE_POSITIVE` or `NOT_USEFUL`.", example = "FALSE_POSITIVE")
        @NotBlank String verdict,
        @Schema(description = "An optional note of at most 280 characters. Do not paste listing text into it. Blank is no note.",
                nullable = true, example = "The word is part of the product name.")
        String note) {
}
