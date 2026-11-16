package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** Whether the catalog took part in a trademark check (ADR-031). */
public enum TrademarkCatalogStatus {
    /** The text was compared with the catalog. */
    SERVED,
    /**
     * The deployment serves no catalog: only the client's watchlist and the rules that need no catalog ran, the result is
     * {@code INCOMPLETE} and the absence of catalog findings says nothing.
     */
    NOT_CONFIGURED,
    /** A value this version of the SDK does not know (a newer server). Never treat it as {@code SERVED}. */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
