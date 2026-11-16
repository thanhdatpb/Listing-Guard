package com.thanhdatpb.listingguard.api.rewrite;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the LLM rewrite suggestions (ADR-037). The feature is off unless {@code enabled} is true and a provider is configured
 * (ADR-036); with it off no bean of this package exists.
 *
 * @param enabled         the master switch of this feature, false by default
 * @param maxPerHour      most runs of one client in one hour, counted in memory per instance
 * @param timeout         how long the model call may take
 * @param maxOutputTokens upper bound on the model's answer
 * @param maxListingChars largest listing (as JSON) that is sent and verified; a bigger one gets no suggestion
 */
@ConfigurationProperties(prefix = "listing-guard.llm.rewrite")
public record RewriteProperties(@DefaultValue("false") boolean enabled, @DefaultValue("20") int maxPerHour,
                                @DefaultValue("20s") Duration timeout, @DefaultValue("900") int maxOutputTokens,
                                @DefaultValue("16000") int maxListingChars) {

    public RewriteProperties {
        if (maxPerHour < 1) {
            throw new IllegalArgumentException("listing-guard.llm.rewrite.max-per-hour must be at least 1.");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("listing-guard.llm.rewrite.timeout must be positive.");
        }
        if (maxOutputTokens < 100) {
            throw new IllegalArgumentException("listing-guard.llm.rewrite.max-output-tokens must be at least 100.");
        }
        if (maxListingChars < 1000) {
            throw new IllegalArgumentException("listing-guard.llm.rewrite.max-listing-chars must be at least 1000.");
        }
    }
}
