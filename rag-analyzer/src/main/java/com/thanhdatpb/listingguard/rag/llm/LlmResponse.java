package com.thanhdatpb.listingguard.rag.llm;

import java.math.BigDecimal;

/**
 * @param text         the model's answer, unparsed
 * @param inputTokens  tokens the vendor counted for the request
 * @param outputTokens tokens the vendor counted for the answer
 * @param reportedCost optional provider-reported amount in its documented units, without inference or conversion
 * @param finishReason why the model stopped, as the vendor reports it (for example {@code stop})
 */
public record LlmResponse(String text, int inputTokens, int outputTokens, String finishReason, BigDecimal reportedCost) {

    public LlmResponse(String text, int inputTokens, int outputTokens, String finishReason) {
        this(text, inputTokens, outputTokens, finishReason, null);
    }

    public LlmResponse {
        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
    }
}
