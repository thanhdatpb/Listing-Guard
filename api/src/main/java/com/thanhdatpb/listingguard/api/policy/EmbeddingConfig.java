package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import com.thanhdatpb.listingguard.rag.embedding.HashingEmbeddingProvider;
import com.thanhdatpb.listingguard.rag.embedding.OllamaEmbeddingProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PolicyProperties.class, EmbeddingProperties.class})
public class EmbeddingConfig {

    /** Building the provider never contacts Ollama, so the application starts without it. */
    @Bean
    public EmbeddingProvider embeddingProvider(EmbeddingProperties properties) {
        EmbeddingProperties.Ollama ollama = properties.ollama();
        return switch (properties.provider()) {
            case "ollama" -> OllamaEmbeddingProvider.nomic(ollama.baseUrl(), ollama.model(), ollama.dimensions(), ollama.timeout());
            case "hashing" -> new HashingEmbeddingProvider(ollama.dimensions());
            default -> throw new IllegalStateException(
                    "Unknown listing-guard.embedding.provider '" + properties.provider() + "' (use ollama)");
        };
    }
}
