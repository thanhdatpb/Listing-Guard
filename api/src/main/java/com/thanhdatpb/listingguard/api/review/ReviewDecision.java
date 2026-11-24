package com.thanhdatpb.listingguard.api.review;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** The actor is supplied by authentication, never by this payload. */
public record ReviewDecision(@NotNull UUID requestId, @NotNull @Min(0) Integer expectedRevision,
                             @NotNull ReviewCaseStatus status, @Schema(nullable = true) Reason reason) {
    public enum Reason {
        FIX_CONFIRMED, NO_ACTION_REQUIRED
    }
}
