package com.thanhdatpb.listingguard.rag.embedding;

import java.util.List;

/**
 * The provider when none is selected ({@code listing-guard.embedding.provider=none}, the default): the application
 * starts and checks listings without any model or key, and everything that needs embeddings (policy ingest,
 * retrieval, its evaluation) fails with a message that says how to turn them on (ADR-012).
 */
public final class DisabledEmbeddingProvider implements EmbeddingProvider {

    public static final String MODEL_ID = "none";

    static final String MESSAGE = "No embedding provider is configured (listing-guard.embedding.provider=none). Policy "
            + "ingest, retrieval and its evaluation need one: set LISTING_GUARD_EMBEDDING_PROVIDER=api (with "
            + "LISTING_GUARD_EMBEDDING_API_KEY) or LISTING_GUARD_EMBEDDING_PROVIDER=ollama. Rule checks do not need it.";

    private final int dimensions;

    public DisabledEmbeddingProvider(int dimensions) {
        this.dimensions = dimensions;
    }

    /** @throws EmbeddingNotConfiguredException if {@code provider} is a {@link DisabledEmbeddingProvider} */
    public static void requireConfigured(EmbeddingProvider provider) {
        if (provider instanceof DisabledEmbeddingProvider) {
            throw new EmbeddingNotConfiguredException(MESSAGE);
        }
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts, Kind kind) {
        throw new EmbeddingNotConfiguredException(MESSAGE);
    }
}
