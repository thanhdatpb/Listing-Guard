package com.thanhdatpb.listingguard.api.review;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Business status and original technical severity are independent. */
public record ReviewCase(UUID id, String pack, UUID checkId, int findingIndex, Marketplace marketplace,
                         Severity severity, ReviewCaseStatus status, int revision, @Schema(nullable = true) UUID reviewerId,
                         @Schema(nullable = true) ReviewDecision.Reason reason, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public enum Marketplace { ETSY, AMAZON }

    public enum Severity { ERROR, WARNING, INFO, HIGH, MEDIUM, LOW }
}
