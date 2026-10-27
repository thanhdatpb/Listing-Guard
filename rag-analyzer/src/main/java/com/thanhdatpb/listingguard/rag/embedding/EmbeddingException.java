package com.thanhdatpb.listingguard.rag.embedding;

/** The embedding backend failed or returned something unusable. */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
