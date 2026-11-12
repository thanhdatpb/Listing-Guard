package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** The state of the deployment's trademark catalog. Anything but {@code READY} means trademark risk is unknown, not low. */
public enum CatalogState {
    READY,
    /** No catalog is configured. */
    DISABLED,
    /** A catalog is configured and cannot be served. */
    REFUSED,
    /** A value this version of the SDK does not know (a newer server). Never treat it as {@code READY}. */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
