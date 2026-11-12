package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** The status of the trademark scan inside a personalization screening. */
public enum TrademarkScanStatus {
    COMPLETE,
    INCOMPLETE,
    /** The deployment has no trademark catalog: no scan ran and the risk is {@code UNKNOWN}. */
    NOT_CONFIGURED,
    /** A value this version of the SDK does not know (a newer server). Never treat it as {@code COMPLETE}. */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
