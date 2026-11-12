package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** What a ruleset limit counts. */
public enum LimitUnit {
    CODE_POINTS,
    ITEMS,
    WORDS,
    UTF8_BYTES,
    OCCURRENCES,
    /** A value this version of the SDK does not know (a newer server). */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
