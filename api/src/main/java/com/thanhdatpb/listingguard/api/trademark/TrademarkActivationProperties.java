package com.thanhdatpb.listingguard.api.trademark;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Whether the trademark runtime serves the catalog version named by the activation log (ADR-009, PR A2d) instead of
 * {@code listing-guard.trademark.catalog-version}.
 *
 * @param follow          serve the version of the latest row of {@code trademark_catalog_activation}; off by default, so a
 *                        deployment keeps serving the configured version. It cannot be combined with a configured version.
 * @param refreshInterval how often the API reads the log again; a new activation is served at the latest after this. The
 *                        one-shot jobs read it once at start-up.
 */
@ConfigurationProperties(prefix = "listing-guard.trademark.activation")
public record TrademarkActivationProperties(
        @DefaultValue("false") boolean follow,
        @DefaultValue("1m") Duration refreshInterval) {

    public TrademarkActivationProperties {
        if (refreshInterval == null || refreshInterval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("listing-guard.trademark.activation.refresh-interval must be at least 1s, got "
                    + refreshInterval);
        }
    }

    /** The default: follow nothing, serve the configured version. */
    public static TrademarkActivationProperties off() {
        return new TrademarkActivationProperties(false, Duration.ofMinutes(1));
    }
}
