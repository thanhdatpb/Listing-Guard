package com.thanhdatpb.listingguard.client.model;

import java.util.List;

/** Tolerant typed result of {@code POST /api/v1/listing-edits}. */
public record ListingEditResult(ListingEditSide before, ListingEditSide after, List<String> changedFields,
                                List<ListingEditFinding> newFindings, List<ListingEditFinding> modifiedFindings,
                                List<ListingEditFinding> remainingFindings) {
}
