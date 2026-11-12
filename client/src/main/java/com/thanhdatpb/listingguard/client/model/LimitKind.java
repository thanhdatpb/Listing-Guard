package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** What a ruleset limit bounds. */
public enum LimitKind {
    MAX_LENGTH,
    MAX_COUNT,
    MAX_BYTES,
    MAX_OCCURRENCES,
    /** A value this version of the SDK does not know (a newer server). */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
