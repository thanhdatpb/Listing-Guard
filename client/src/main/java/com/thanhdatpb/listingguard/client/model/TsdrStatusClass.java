package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** What a USPTO TSDR status code means for a mark: alive, dead, or not settled by the deployment's status map. */
public enum TsdrStatusClass {
    /** Registered or pending. */
    LIVE,
    /** Abandoned, cancelled or expired. */
    DEAD,
    /** A code the deployment's status map does not settle. Never the same as {@link #LIVE}. */
    UNKNOWN,
    /** A value this version of the SDK does not know (a newer server). */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
