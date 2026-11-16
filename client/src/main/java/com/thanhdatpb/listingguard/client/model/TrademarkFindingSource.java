package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** Where a trademark finding comes from (schema {@code TrademarkCheckFindingView}, member {@code source}; ADR-031). */
public enum TrademarkFindingSource {
    /** A mark of the catalog. */
    CATALOG,
    /** A rule about place, sport or color. */
    CONTEXT,
    /** An entry of the client's own watchlist. */
    WATCHLIST,
    /** A value this version of the SDK does not know (a newer server). Never treat it as {@code CATALOG}. */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
