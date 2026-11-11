package com.thanhdatpb.listingguard.api.image;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long image check results are kept and how the scheduled cleanup runs (ADR-008 "Expiry and cleanup", ADR-010). The period
 * is an operational proposal, not a marketplace rule. Expiry is enforced by GET and by the cache lookup whatever the cleanup
 * does, so a late or disabled cleanup only leaves rows behind.
 *
 * @param period              how long a result, its hashes and its evidence are kept after it is stored
 * @param cleanupEnabled      whether this instance triggers the cleanup on a schedule. Any number of instances may: only one
 *                            performs a batch at a time (an advisory lock, see {@link ImageRetentionCleanup})
 * @param cleanupInterval     time between the end of one cleanup run and the start of the next
 * @param cleanupInitialDelay time between start-up and the first run. Four minutes by default, one more than the personalization
 *                            cleanup, so that the cleanups rarely meet at the shared lock
 * @param cleanupBatchSize    most check rows deleted by one batch (one transaction); items and findings go with them
 */
@ConfigurationProperties(prefix = "listing-guard.image.retention")
public record ImageRetentionProperties(
        @DefaultValue("30d") Duration period,
        @DefaultValue("true") boolean cleanupEnabled,
        @DefaultValue("1h") Duration cleanupInterval,
        @DefaultValue("4m") Duration cleanupInitialDelay,
        @DefaultValue("500") int cleanupBatchSize) {

    public ImageRetentionProperties {
        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("listing-guard.image.retention.period must be positive");
        }
        if (cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalArgumentException("listing-guard.image.retention.cleanup-interval must be positive");
        }
        if (cleanupInitialDelay.isNegative()) {
            throw new IllegalArgumentException("listing-guard.image.retention.cleanup-initial-delay must not be negative");
        }
        if (cleanupBatchSize < 1) {
            throw new IllegalArgumentException("listing-guard.image.retention.cleanup-batch-size must be at least 1");
        }
    }
}
