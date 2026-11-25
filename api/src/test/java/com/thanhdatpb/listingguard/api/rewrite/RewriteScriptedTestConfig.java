package com.thanhdatpb.listingguard.api.rewrite;

import com.thanhdatpb.listingguard.api.llm.LlmCache;
import com.thanhdatpb.listingguard.api.llm.LlmClient;
import com.thanhdatpb.listingguard.api.llm.LlmUsageStore;
import com.thanhdatpb.listingguard.rag.llm.ScriptedLlmProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Puts a {@link ScriptedLlmProvider} behind a shared {@code LlmClient} of ADR-036 (one provider, listing text allowed), for the tests of
 * the repair rounds (ADR-050). A test scripts an answer for a round by matching its marker in the prompt, so the scripts of different
 * tests never meet. The provider answers {@code {"candidates":[]}} when no script matches, and the client caches every answer, so a
 * repeated prompt is not asked twice: exactly what the tests of "a cache hit is still verified" need.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RewriteScriptedTestConfig {

    @Bean
    ScriptedLlmProvider scriptedLlm() {
        return new ScriptedLlmProvider("scripted:v1", "{\"candidates\":[]}");
    }

    @Bean
    @Primary
    LlmClient rewriteScriptedLlmClient(ScriptedLlmProvider scripted) {
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
        return new LlmClient(List.of(new LlmClient.Endpoint("scripted", scripted, 100_000, 1_000_000, true, false)), store,
                new LlmCache(1000, Duration.ofHours(1), Clock.systemUTC()), Clock.systemUTC());
    }
}
