package com.thanhdatpb.listingguard.api.policy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding backend selection (ADR-004).
 *
 * @param provider {@code ollama} for real use; {@code hashing} is a deterministic fake for tests only
 */
@ConfigurationProperties(prefix = "listing-guard.embedding")
public record EmbeddingProperties(@DefaultValue("ollama") String provider, @DefaultValue Ollama ollama) {

    public record Ollama(
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("nomic-embed-text") String model,
            @DefaultValue("768") int dimensions,
            @DefaultValue("60s") Duration timeout) {
    }
}
