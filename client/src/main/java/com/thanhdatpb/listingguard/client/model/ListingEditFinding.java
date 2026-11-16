package com.thanhdatpb.listingguard.client.model;

import java.util.Map;

/** A pack-specific finding before and after the edit, with the exact stored feedback target. */
public record ListingEditFinding(String pack, Map<String, Object> before, Map<String, Object> after,
                                 ListingEditFeedbackTarget feedbackTarget) {
}
