package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** Unverified catalog distinctiveness signals used for a tier-based trademark adjustment. */
public record TrademarkCalibrationEvidence(
        Tier tier,
        Integer wordCount,
        Long sameMarkCount,
        Integer sameMarkClassCount,
        Boolean dictionaryWord,
        MarkKind markKind,
        Reason reason) {

    public enum Tier {
        STRONG, ORDINARY, WEAK,
        @JsonEnumDefaultValue UNRECOGNIZED
    }

    public enum MarkKind {
        STANDARD_CHARACTER, WORDS_WITH_DESIGN, OTHER,
        @JsonEnumDefaultValue UNRECOGNIZED
    }

    public enum Reason {
        DICTIONARY_UNAVAILABLE, DRAWING_UNKNOWN, COMMON_OR_REPEATED, UNIQUE_MULTIWORD, DEFAULT,
        @JsonEnumDefaultValue UNRECOGNIZED
    }
}
