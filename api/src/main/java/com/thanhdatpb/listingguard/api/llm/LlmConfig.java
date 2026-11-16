package com.thanhdatpb.listingguard.api.llm;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Builds the shared {@link LlmClient} (ADR-036). With {@code listing-guard.llm.enabled=false} (the default) it is the disabled
 * client: no provider object is built, no key is read. With it on, a provider without a key is skipped and, when none is left,
 * the application refuses to start, as ADR-012 item 5 does for an empty embedding key.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    @Bean
    public LlmClient llmClient(LlmProperties properties, ObjectProvider<JdbcTemplate> jdbc, Clock clock) {
        if (!properties.enabled()) {
            return LlmClient.disabled(clock);
        }
        List<LlmClient.Endpoint> endpoints = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (LlmProperties.Provider provider : properties.providers()) {
            if (provider.id() == null || !ID.matcher(provider.id()).matches() || !ids.add(provider.id())) {
                throw new IllegalStateException("listing-guard.llm.providers needs a unique id of [a-z0-9_-]{1,32}, got '"
                        + provider.id() + "'");
            }
            if (!provider.usable()) {
                log.info("LLM provider '{}' has no key and is skipped", provider.id());
                continue;
            }
            if (provider.requestsPerMinute() < 1 || provider.requestsPerDay() < 1) {
                throw new IllegalStateException("LLM provider '" + provider.id() + "' needs requests-per-minute and requests-per-day"
                        + " of at least 1");
            }
            endpoints.add(new LlmClient.Endpoint(provider.id(), new OpenAiCompatibleProvider(provider.id(), provider.baseUrl(),
                    provider.model(), provider.key(), provider.jsonMode()), provider.requestsPerMinute(),
                    provider.requestsPerDay(), provider.listingTextAllowed(), provider.personalDataAllowed()));
        }
        if (endpoints.isEmpty()) {
            throw new IllegalStateException("listing-guard.llm.enabled=true but no provider has a key: set one of "
                    + "LISTING_GUARD_LLM_GROQ_KEY, LISTING_GUARD_LLM_CLOUDFLARE_KEY or LISTING_GUARD_LLM_GEMINI_KEY (docs/llm.md)");
        }
        endpoints.forEach(endpoint -> log.info("LLM provider {} model {} ({} requests/min, {}/day, listing text {}, personal data {})",
                endpoint.id(), endpoint.provider().modelId(), endpoint.requestsPerMinute(), endpoint.requestsPerDay(),
                endpoint.listingTextAllowed() ? "allowed" : "not allowed", endpoint.personalDataAllowed() ? "allowed" : "not allowed"));
        LlmCache cache = new LlmCache(properties.cache().maxEntries(), properties.cache().ttl(), clock);
        return new LlmClient(endpoints, new JdbcLlmUsageStore(jdbc.getObject()), cache, clock);
    }
}
