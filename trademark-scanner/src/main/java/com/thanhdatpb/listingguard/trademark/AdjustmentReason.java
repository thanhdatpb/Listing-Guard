package com.thanhdatpb.listingguard.trademark;

import java.util.Arrays;

/**
 * Why a catalog finding carries a lower level than the one its rule assigns (ADR-009, "Product classes and generic
 * words"). The {@link #code()} is the stable wire form used in the API and in the database.
 */
public enum AdjustmentReason {

    /** The request named a product type, the mark has Nice classes and none of them is one of the product type's. */
    OUTSIDE_PRODUCT_CLASSES("outside_product_classes"),

    /** The mark is a word in the ruleset's {@code generic_words} and the match has no brand context. */
    COMMON_WORD("common_word"),

    /**
     * A language model read the sentence around the match and said the word probably describes the product instead of naming a
     * brand (ADR-036). Only ever added by {@link LlmJudgement}, which can lower a MEDIUM catalog finding to LOW and does nothing else.
     */
    LLM_DESCRIPTIVE("llm_descriptive");

    private final String code;

    AdjustmentReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static AdjustmentReason ofCode(String code) {
        return Arrays.stream(values()).filter(reason -> reason.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown risk adjustment reason: " + code));
    }
}
