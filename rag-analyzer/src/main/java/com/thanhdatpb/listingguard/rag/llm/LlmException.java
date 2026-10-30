package com.thanhdatpb.listingguard.rag.llm;

/** The model call failed or returned something unusable. Messages never carry keys or listing text. */
public class LlmException extends RuntimeException {

    public enum Kind {
        TIMEOUT,
        RATE_LIMIT,
        BAD_STATUS,
        /** The answer hit {@code maxOutputTokens}, so the JSON is incomplete. */
        TRUNCATED
    }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
