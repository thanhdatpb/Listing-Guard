package com.thanhdatpb.listingguard.api.llm;

import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "listing-guard.analyzer", name = "enabled", havingValue = "true")
class PolicyLlmConfig {

    @Bean
    LlmProvider policyLlmProvider(
            @Value("${listing-guard.analyzer.llm.base-url:}") String baseUrl,
            @Value("${listing-guard.analyzer.llm.model:}") String model,
            @Value("${LISTING_GUARD_ANALYZER_LLM_API_KEY:}") String key,
            @Value("${listing-guard.analyzer.llm.json-mode:true}") boolean jsonMode) {
        return new ApiLlmProvider(baseUrl, model, key, jsonMode);
    }
}
