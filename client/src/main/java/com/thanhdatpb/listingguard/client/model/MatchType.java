package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** How a trademark finding matched. */
public enum MatchType {
    EXACT,
    PHRASE,
    FUZZY,
    CONTEXT,
    /** A value this version of the SDK does not know (a newer server). */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
