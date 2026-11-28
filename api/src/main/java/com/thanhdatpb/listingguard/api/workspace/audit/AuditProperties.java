package com.thanhdatpb.listingguard.api.workspace.audit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The settings of the workspace's background audit (ADR-061). Off by default; it needs {@code listing-guard.workspace.enabled} and the console.
 *
 * @param enabled       turns on the queue, the worker, the triggers, the pages and the highlights
 * @param autoStart     whether the worker threads start with the application (a test turns it off and runs the queue by hand)
 * @param concurrency   how many runs are worked on at once (each on its own thread), 1 to 8
 * @param pollInterval  how long an idle worker waits before it asks the queue again
 * @param lease         how long a run stays reserved without a heartbeat before another worker may take it again
 * @param driftInterval how often the worker compares the analysis context of each client with the one of its last audit
 * @param scheduleCron  a Spring cron expression for the periodic audit of every account; empty (the default) means no periodic audit
 * @param keepRuns      how many finished runs of one seller account are kept
 * @param maxFindings   the most findings kept for one listing
 * @param cloneLeftover whether a clone is also compared with its original (ADR-039, pack {@code clone-leftover}); off by default, and with it off an
 *                      audit is exactly what it was: the same findings, the same analysis versions, no clone is audited again
 */
@ConfigurationProperties(prefix = "listing-guard.workspace.audit")
public record AuditProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean autoStart,
        @DefaultValue("2") int concurrency,
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("30s") Duration driftInterval,
        @DefaultValue("") String scheduleCron,
        @DefaultValue("30") int keepRuns,
        @DefaultValue("100") int maxFindings,
        @DefaultValue("false") boolean cloneLeftover) {

    public AuditProperties {
        if (concurrency < 1 || concurrency > 8) {
            throw new IllegalArgumentException("listing-guard.workspace.audit.concurrency must be from 1 to 8.");
        }
        if (keepRuns < 1 || maxFindings < 1) {
            throw new IllegalArgumentException("listing-guard.workspace.audit: keep-runs and max-findings must be at least 1.");
        }
        for (Duration duration : new Duration[] {pollInterval, lease, driftInterval}) {
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("listing-guard.workspace.audit: poll-interval, lease and drift-interval must be positive.");
            }
        }
        if (!scheduleCron.isBlank()) {
            try {
                org.springframework.scheduling.support.CronExpression.parse(scheduleCron.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("listing-guard.workspace.audit.schedule-cron is not a valid cron expression: " + e.getMessage());
            }
        }
    }

    public boolean scheduled() {
        return !scheduleCron.isBlank();
    }
}
