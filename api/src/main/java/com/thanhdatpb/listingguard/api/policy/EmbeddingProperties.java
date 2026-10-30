package com.thanhdatpb.listingguard.api.policy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding backend selection (ADR-004, ADR-012).
 *
 * @param provider {@code api} (the default, a hosted embedding API), {@code ollama} (optional, a local server) or
 *                 {@code hashing}, a deterministic fake for tests and the admin job only
 */
@ConfigurationProperties(prefix = "listing-guard.embedding")
public record EmbeddingProperties(
        @DefaultValue("api") String provider,
        @DefaultValue Api api,
        @DefaultValue Ollama ollama) {

    /**
     * The hosted embedding API (Google Gemini, ADR-012). {@code key} comes from
     * {@code LISTING_GUARD_EMBEDDING_API_KEY} and is never printed.
     *
     * @param dimensions   must match the {@code vector(768)} column of {@code policy_chunk}
     * @param batchSize    texts per HTTP call; the vendor's maximum is not stated, so it is configurable
     * @param retryBackoff wait before the first retry; it doubles for each further retry
     */
    public record Api(
            @DefaultValue("https://generativelanguage.googleapis.com/v1beta") String baseUrl,
            @DefaultValue("gemini-embedding-2") String model,
            @DefaultValue("768") int dimensions,
            @DefaultValue("") String key,
            @DefaultValue("16") int batchSize,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("1s") Duration retryBackoff) {

        @Override
        public String toString() {
            return "Api[baseUrl=" + baseUrl + ", model=" + model + ", dimensions=" + dimensions
                    + ", key=" + (key == null || key.isBlank() ? "<empty>" : "<set>") + ", batchSize=" + batchSize
                    + ", timeout=" + timeout + ", retryBackoff=" + retryBackoff + "]";
        }
    }

    public record Ollama(
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("nomic-embed-text") String model,
            @DefaultValue("768") int dimensions,
            @DefaultValue("60s") Duration timeout) {
    }
}
