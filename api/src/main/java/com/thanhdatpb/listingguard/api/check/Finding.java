package com.thanhdatpb.listingguard.api.check;

/** A single issue found in a listing. Suggestions are advice only; nothing is auto-applied. */
public record Finding(
        FindingSource source,
        Severity severity,
        String field,
        String message,
        String suggestion,
        String citation) {
}
