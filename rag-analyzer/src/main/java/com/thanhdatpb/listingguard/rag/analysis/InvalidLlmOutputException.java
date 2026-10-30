package com.thanhdatpb.listingguard.rag.analysis;

/**
 * The model's answer is not a JSON object with a {@code findings} array, so there is nothing to keep
 * ({@code LLM_INVALID_OUTPUT} in ADR-011 item 8). Per-item mismatches do not throw: they are dropped and counted.
 */
public class InvalidLlmOutputException extends RuntimeException {

    public InvalidLlmOutputException(String message) {
        super(message);
    }

    public InvalidLlmOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
