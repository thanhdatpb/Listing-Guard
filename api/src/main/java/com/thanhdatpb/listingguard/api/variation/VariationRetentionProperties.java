package com.thanhdatpb.listingguard.api.variation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long variation check results are kept and how the scheduled cleanup runs (ADR-015, which follows ADR-009 "Data,
 * privacy and retention" and ADR-010). The period is an operational proposal, not a legal or marketplace guarantee. Expiry
 * is enforced by GET and by the cache lookup whatever the cleanup does, so a late or disabled cleanup only leaves rows
 * behind.
 *
 * @param period              how long a result, its hash and its evidence are kept after it is stored
 * @param cleanupEnabled      whether this instance triggers the cleanup on a schedule. Any number of instances may: only one
 *                            performs a batch at a time (an advisory lock, see {@link VariationRetentionCleanup})
 * @param cleanupInterval     time between the end of one cleanup run and the start of the next
 * @param cleanupInitialDelay time between start-up and the first run. Two minutes by default, one more than the trademark
 *                            cleanup, so that the two rarely meet at the shared lock
 * @param cleanupBatchSize    most check rows deleted by one batch (one transaction); findings go with them
 */
@ConfigurationProperties(prefix = "listing-guard.variation.retention")
public record VariationRetentionProperties(
        @DefaultValue("30d") Duration period,
        @DefaultValue("true") boolean cleanupEnabled,
        @DefaultValue("1h") Duration cleanupInterval,
        @DefaultValue("2m") Duration cleanupInitialDelay,
        @DefaultValue("500") int cleanupBatchSize) {

    public VariationRetentionProperties {
        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("listing-guard.variation.retention.period must be positive");
        }
        if (cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalArgumentException("listing-guard.variation.retention.cleanup-interval must be positive");
        }
        if (cleanupInitialDelay.isNegative()) {
            throw new IllegalArgumentException("listing-guard.variation.retention.cleanup-initial-delay must not be negative");
        }
        if (cleanupBatchSize < 1) {
            throw new IllegalArgumentException("listing-guard.variation.retention.cleanup-batch-size must be at least 1");
        }
    }
}
