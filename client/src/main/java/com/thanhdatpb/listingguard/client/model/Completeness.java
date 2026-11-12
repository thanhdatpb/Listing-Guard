package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** Whether every rule of a pack ran. {@code INCOMPLETE} is never a clean result. */
public enum Completeness {
    /** Every rule ran. */
    COMPLETE,
    /** A rule failed; the findings established are kept, but the result is not clean. */
    INCOMPLETE,
    /** A value this version of the SDK does not know (a newer server). Never treat it as {@code COMPLETE}. */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
