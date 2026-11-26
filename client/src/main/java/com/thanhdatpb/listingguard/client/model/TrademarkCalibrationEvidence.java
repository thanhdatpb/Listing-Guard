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
        Reason reason,
        Long ownerCountSameMark) {

    public TrademarkCalibrationEvidence(Tier tier, Integer wordCount, Long sameMarkCount, Integer sameMarkClassCount,
                                        Boolean dictionaryWord, MarkKind markKind, Reason reason) {
        this(tier, wordCount, sameMarkCount, sameMarkClassCount, dictionaryWord, markKind, reason, null);
    }

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
        OWNERS_UNKNOWN, CROWDED_OWNERS, DICTIONARY_FEW_OWNERS, UNIQUE_OWNER_WORD,
        @JsonEnumDefaultValue UNRECOGNIZED
    }
}
