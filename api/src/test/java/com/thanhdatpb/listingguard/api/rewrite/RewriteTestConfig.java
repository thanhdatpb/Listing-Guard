package com.thanhdatpb.listingguard.api.rewrite;

import com.thanhdatpb.listingguard.api.llm.LlmCache;
import com.thanhdatpb.listingguard.api.llm.LlmClient;
import com.thanhdatpb.listingguard.api.llm.LlmUsageStore;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Puts a {@link StubLlm} behind a shared {@code LlmClient} of ADR-036 (one provider, listing text allowed). The client is
 * {@code @Primary}, so it is the one that gets injected next to the disabled client {@code LlmConfig} builds by default.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RewriteTestConfig {

    @Bean
    StubLlm stubLlm() {
        return new StubLlm();
    }

    @Bean
    @Primary
    LlmClient rewriteStubLlmClient(StubLlm stub) {
        LlmUsageStore store = new LlmUsageStore() {
            @Override
            public boolean reserve(String providerId, LocalDate day, int limit) {
                return true;
            }

            @Override
            public void addTokens(String providerId, LocalDate day, long inputTokens, long outputTokens) {
            }

            @Override
            public Map<String, Long> requestsOn(LocalDate day) {
                return Map.of();
            }
        };
        return new LlmClient(List.of(new LlmClient.Endpoint("stub", stub, 100_000, 1_000_000, true, false)), store,
                new LlmCache(100, Duration.ofHours(1), Clock.systemUTC()), Clock.systemUTC());
    }
}
