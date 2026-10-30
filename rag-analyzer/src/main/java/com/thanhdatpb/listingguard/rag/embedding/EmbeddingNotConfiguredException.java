package com.thanhdatpb.listingguard.rag.embedding;

/** No embedding provider is selected ({@code none}); the caller needs one. A configuration state, not a backend failure. */
public class EmbeddingNotConfiguredException extends EmbeddingException {

    public EmbeddingNotConfiguredException(String message) {
        super(message);
    }
}
