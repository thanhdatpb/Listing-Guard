package com.thanhdatpb.listingguard.api.feedback;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * The answer of {@code POST /api/v1/feedback} (ADR-034): the verdict as stored, with what was copied of the finding. It holds no
 * text of the listing; the note is the caller's own.
 */
@JsonPropertyOrder({"pack", "checkId", "findingIndex", "marketplace", "ruleId", "source", "level", "analysisVersion", "verdict",
        "note", "created", "createdAt", "updatedAt"})
public record FeedbackResult(
        @Schema(example = "listing-rules") String pack,
        UUID checkId,
        @Schema(example = "0") int findingIndex,
        @Schema(example = "ETSY") String marketplace,
        @Schema(example = "title_repeated_words") String ruleId,
        @Schema(description = "`RULE`, `CATALOG` (a trademark finding that matched a catalog mark) or `WATCHLIST` (reserved).",
                example = "RULE") String source,
        @Schema(description = "The finding's severity (`ERROR`, `WARNING`, `INFO`) or trademark risk (`HIGH`, `MEDIUM`, `LOW`).",
                example = "WARNING") String level,
        @Schema(description = "The analysisVersion of the result the finding belongs to.", example = "etsy-2026-12") String analysisVersion,
        @Schema(example = "FALSE_POSITIVE") String verdict,
        @Schema(nullable = true, example = "The word is part of the product name.") String note,
        @Schema(description = "True when this call stored the first verdict of this API key on the finding; false when it "
                + "replaced an earlier one.") boolean created,
        Instant createdAt,
        Instant updatedAt) {

    static FeedbackResult of(StoredVerdict stored) {
        return new FeedbackResult(stored.target().pack(), stored.target().checkId(), stored.target().position(),
                stored.marketplace(), stored.ruleId(), stored.source().name(), stored.level(), stored.analysisVersion(),
                stored.verdict().name(), stored.note(), stored.created(), stored.createdAt(), stored.updatedAt());
    }
}
