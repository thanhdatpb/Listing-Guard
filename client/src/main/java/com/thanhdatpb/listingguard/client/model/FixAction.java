package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** What a fix suggestion would do. A suggestion is advice and is never applied by Listing Guard. */
public enum FixAction {
    /** Shorten the value to {@code proposedValue}. */
    REPLACE,
    /** Remove the value (a repeated tag). */
    REMOVE,
    /** A value this version of the SDK does not know (a newer server). */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
