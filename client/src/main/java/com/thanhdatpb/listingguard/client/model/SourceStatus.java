package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** How well a rule's number or list is sourced. */
public enum SourceStatus {
    /** From an official page of the marketplace. */
    VERIFIED,
    /** The official page could not be read; consistent secondary sources. */
    SECONDARY,
    /** Not verified. */
    UNVERIFIED,
    /** A value this version of the SDK does not know (a newer server). Never treat it as one of the others. */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
