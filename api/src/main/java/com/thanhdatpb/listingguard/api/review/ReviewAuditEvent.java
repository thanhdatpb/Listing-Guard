package com.thanhdatpb.listingguard.api.review;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** No credentials, check content, or client-supplied actor text are retained. */
public record ReviewAuditEvent(int revision, @Schema(nullable = true) UUID requestId, UUID actorId, @Schema(nullable = true) ReviewCaseStatus previousStatus,
                               ReviewCaseStatus status, @Schema(nullable = true) ReviewDecision.Reason reason, OffsetDateTime occurredAt) {
}
