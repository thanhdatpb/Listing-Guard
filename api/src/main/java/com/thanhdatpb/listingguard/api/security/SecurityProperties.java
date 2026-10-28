package com.thanhdatpb.listingguard.api.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Clients and keys live in the database (ADR-007). What is configured here:
 *
 * @param devApiKey        development only: a key registered at start-up as client {@code dev}; refused unless the
 *                         {@code dev} profile is active. Empty by default.
 * @param authCacheTtl     how long the answer for a key is remembered; revoking a key or deactivating a client takes
 *                         up to this long to take effect. Zero turns the cache off.
 * @param lastUsedInterval at most one write of {@code api_key.last_used_at} per key in this time
 */
@ConfigurationProperties(prefix = "listing-guard.security")
public record SecurityProperties(
        @DefaultValue("") String devApiKey,
        @DefaultValue("30s") Duration authCacheTtl,
        @DefaultValue("1m") Duration lastUsedInterval) {
}
