package com.thanhdatpb.listingguard.api.personalization;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long personalization screening results are kept and how the scheduled cleanup runs (ADR-016, which follows ADR-009
 * "Data, privacy and retention" and ADR-010). The period is an operational proposal, not a legal or marketplace guarantee.
 * Expiry is enforced by GET and by the cache lookup whatever the cleanup does, so a late or disabled cleanup only leaves rows
 * behind.
 *
 * @param period              how long a result, its hash and its evidence are kept after it is stored
 * @param cleanupEnabled      whether this instance triggers the cleanup on a schedule. Any number of instances may: only one
 *                            performs a batch at a time (an advisory lock, see {@link PersonalizationRetentionCleanup})
 * @param cleanupInterval     time between the end of one cleanup run and the start of the next
 * @param cleanupInitialDelay time between start-up and the first run. Three minutes by default, one more than the variation
 *                            cleanup and two more than the trademark one, so that the three rarely meet at the shared lock
 * @param cleanupBatchSize    most check rows deleted by one batch (one transaction); findings go with them
 */
@ConfigurationProperties(prefix = "listing-guard.personalization.retention")
public record PersonalizationRetentionProperties(
        @DefaultValue("30d") Duration period,
        @DefaultValue("true") boolean cleanupEnabled,
        @DefaultValue("1h") Duration cleanupInterval,
        @DefaultValue("3m") Duration cleanupInitialDelay,
        @DefaultValue("500") int cleanupBatchSize) {

    public PersonalizationRetentionProperties {
        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("listing-guard.personalization.retention.period must be positive");
        }
        if (cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalArgumentException("listing-guard.personalization.retention.cleanup-interval must be positive");
        }
        if (cleanupInitialDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "listing-guard.personalization.retention.cleanup-initial-delay must not be negative");
        }
        if (cleanupBatchSize < 1) {
            throw new IllegalArgumentException(
                    "listing-guard.personalization.retention.cleanup-batch-size must be at least 1");
        }
    }
}
