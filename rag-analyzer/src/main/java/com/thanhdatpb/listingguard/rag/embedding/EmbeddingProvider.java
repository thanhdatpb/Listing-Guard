package com.thanhdatpb.listingguard.rag.embedding;

import java.util.List;

/**
 * Turns text into vectors. The rest of the system depends on this interface only, so the model or
 * the provider can be replaced (ADR-004).
 */
public interface EmbeddingProvider {

    /** Some models embed documents and queries differently (task prefixes), so callers say which one. */
    enum Kind {
        DOCUMENT,
        QUERY
    }

    /**
     * Identifies provider, model and dimensions, for example {@code ollama:nomic-embed-text:768}. It is
     * stored with every ingested document so that changing the model triggers re-embedding.
     */
    String modelId();

    int dimensions();

    /** One vector per text, same order. Every vector has {@link #dimensions()} elements. */
    List<float[]> embed(List<String> texts, Kind kind);

    default float[] embed(String text, Kind kind) {
        return embed(List.of(text), kind).get(0);
    }
}
