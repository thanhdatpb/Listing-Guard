package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** Where a finding comes from. */
public enum FindingSource {
    /** A deterministic rule (the only source today). */
    RULE,
    /** Reserved for a policy-grounded LLM step. */
    LLM,
    /** A value this version of the SDK does not know (a newer server). */
    @JsonEnumDefaultValue
    UNRECOGNIZED
}
