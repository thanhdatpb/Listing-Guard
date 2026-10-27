package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.rag.policy.PolicyChunker;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where policy documents live and how they are chunked.
 *
 * @param dir            directory holding {@code manifest.yaml} and {@code raw/<marketplace>/}
 * @param searchEndpoint the debug endpoint {@code GET /policies/search}; off by default because there is no
 *                       authentication yet
 */
@ConfigurationProperties(prefix = "listing-guard.policies")
public record PolicyProperties(
        @DefaultValue("policies") String dir,
        @DefaultValue Chunking chunking,
        @DefaultValue SearchEndpoint searchEndpoint) {

    public record Chunking(@DefaultValue("1500") int maxChars, @DefaultValue("200") int minChars) {
    }

    public record SearchEndpoint(@DefaultValue("false") boolean enabled) {
    }

    public PolicyChunker.Config chunkerConfig() {
        return new PolicyChunker.Config(chunking.maxChars(), chunking.minChars());
    }
}
