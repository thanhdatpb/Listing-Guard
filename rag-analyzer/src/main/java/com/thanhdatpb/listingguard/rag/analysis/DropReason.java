package com.thanhdatpb.listingguard.rag.analysis;

import java.util.Locale;

/** Why a proposed finding was removed (ADR-011 item 6). */
public enum DropReason {
    UNKNOWN_CHUNK,
    QUOTE_NOT_FOUND,
    /** Too short, or contains an ellipsis. */
    QUOTE_LENGTH,
    VALUE_NOT_FOUND,
    SCHEMA_INVALID;

    /** The name used in logs and the response, for example {@code quote_not_found}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
