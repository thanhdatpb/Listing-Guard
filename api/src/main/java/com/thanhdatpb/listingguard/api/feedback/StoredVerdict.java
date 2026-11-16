package com.thanhdatpb.listingguard.api.feedback;

import java.time.Instant;

/**
 * One stored verdict as the service returns it (ADR-034). It holds no text of the listing: only the address, what was copied of
 * the finding when the verdict was given, the verdict and the reviewer's own note.
 *
 * @param created true when this call made the row; false when it changed an earlier verdict of the same reviewer
 */
public record StoredVerdict(FeedbackTarget target, String marketplace, String ruleId, FeedbackSource source, String level,
                            String analysisVersion, FeedbackVerdict verdict, String note, Instant createdAt, Instant updatedAt,
                            boolean created) {
}
