package com.thanhdatpb.listingguard.rag.llm;

import java.time.Duration;

/**
 * @param system          fixed instructions
 * @param user            the untrusted part: policy context and listing text, marked as data
 * @param jsonSchema      JSON Schema of the answer, as text
 * @param maxOutputTokens upper bound on the answer length
 * @param timeout         how long the call may take
 */
public record LlmRequest(String system, String user, String jsonSchema, int maxOutputTokens, Duration timeout) {

    public LlmRequest {
        if (system == null || user == null || jsonSchema == null) {
            throw new IllegalArgumentException("system, user and jsonSchema are required");
        }
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
