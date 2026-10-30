package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.rag.embedding.DisabledEmbeddingProvider;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import com.thanhdatpb.listingguard.rag.embedding.HashingEmbeddingProvider;
import com.thanhdatpb.listingguard.rag.embedding.OllamaEmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PolicyProperties.class, EmbeddingProperties.class})
public class EmbeddingConfig {

    static final String KEY_VARIABLE = "LISTING_GUARD_EMBEDDING_API_KEY";

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    /**
     * Building the provider never contacts the backend, so the application starts without it. The default
     * {@code none} needs no model and no key: rule checks work, and what needs embeddings fails when it is used
     * (ADR-012). Only with provider {@code api} does an empty key stop the start-up here: this bean exists in the web
     * application and in the one-shot jobs {@code ingest} and {@code eval-retrieval} alike, which have no web server
     * and so no {@code StartupSafetyCheck}. {@code admin} and the tests use {@code hashing} and need no key.
     */
    @Bean
    public EmbeddingProvider embeddingProvider(EmbeddingProperties properties) {
        EmbeddingProperties.Ollama ollama = properties.ollama();
        EmbeddingProvider provider = switch (properties.provider()) {
            case "none" -> new DisabledEmbeddingProvider(ollama.dimensions());
            case "api" -> api(properties.api());
            case "ollama" -> OllamaEmbeddingProvider.nomic(ollama.baseUrl(), ollama.model(), ollama.dimensions(), ollama.timeout());
            case "hashing" -> new HashingEmbeddingProvider(ollama.dimensions());
            default -> throw new IllegalStateException("Unknown listing-guard.embedding.provider '" + properties.provider()
                    + "' (use none, api, ollama or hashing)");
        };
        if (provider instanceof DisabledEmbeddingProvider) {
            log.info("Embedding provider: none. Rule checks work; policy ingest and retrieval are off until "
                    + "listing-guard.embedding.provider is api or ollama.");
        } else {
            log.info("Embedding provider: {}", provider.modelId());
        }
        return provider;
    }

    private static ApiEmbeddingProvider api(EmbeddingProperties.Api api) {
        if (api.key() == null || api.key().isBlank()) {
            throw new IllegalStateException("listing-guard.embedding.provider is 'api' but no key is set. Set "
                    + KEY_VARIABLE + " (the key of the embedding API), or choose another provider with "
                    + "LISTING_GUARD_EMBEDDING_PROVIDER=ollama, or none to run without policy retrieval.");
        }
        return new ApiEmbeddingProvider(api.baseUrl(), api.model(), api.dimensions(), api.key(), api.batchSize(),
                api.timeout(), api.retryBackoff());
    }
}
