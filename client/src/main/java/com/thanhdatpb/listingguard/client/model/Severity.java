package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** How serious a finding is. */
public enum Severity {
    /** The marketplace will refuse it, or it is invalid. */
    ERROR,
    /** Worth fixing. */
    WARNING,
    /** For information. */
    INFO,
    /** A value this version of the SDK does not know (a newer server). Never treat it as one of the others. */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
