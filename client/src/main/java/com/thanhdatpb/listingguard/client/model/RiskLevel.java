package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** A trademark risk level. {@code UNKNOWN} is never {@code LOW}. */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    /** The risk could not be determined (a rule failed, no catalog): never read it as {@code LOW}. */
    UNKNOWN,
    /** A value this version of the SDK does not know (a newer server). Never treat it as {@code LOW}. */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
