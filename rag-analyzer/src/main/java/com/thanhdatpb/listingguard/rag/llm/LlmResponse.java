package com.thanhdatpb.listingguard.rag.llm;

/**
 * @param text         the model's answer, unparsed
 * @param inputTokens  tokens the vendor counted for the request
 * @param outputTokens tokens the vendor counted for the answer
 * @param finishReason why the model stopped, as the vendor reports it (for example {@code stop})
 */
public record LlmResponse(String text, int inputTokens, int outputTokens, String finishReason) {

    public LlmResponse {
        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
    }
}
