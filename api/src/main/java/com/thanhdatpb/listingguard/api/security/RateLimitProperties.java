package com.thanhdatpb.listingguard.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per API key rate limit: a token bucket holding up to {@code burst} requests that refills at
 * {@code requestsPerMinute} per minute. In memory, so it limits one application instance (ADR-005).
 */
@ConfigurationProperties(prefix = "listing-guard.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60") int requestsPerMinute,
        @DefaultValue("20") int burst) {

    public RateLimitProperties {
        if (requestsPerMinute < 1 || burst < 1) {
            throw new IllegalArgumentException("listing-guard.rate-limit.requests-per-minute and burst must be at least 1");
        }
    }
}
