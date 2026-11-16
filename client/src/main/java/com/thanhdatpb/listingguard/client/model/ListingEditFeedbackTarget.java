package com.thanhdatpb.listingguard.client.model;

import java.util.UUID;

/** Stable address of the stored finding used by the finding-feedback API. */
public record ListingEditFeedbackTarget(String pack, UUID checkId, int position) {
}
