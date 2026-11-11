package com.thanhdatpb.listingguard.api.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Runs {@link ImageRetentionCleanup} on a schedule in the API process (ADR-008, ADR-010). It exists only in a web application,
 * so the one-shot jobs, which have no web server, never start it. It runs whether or not image checks are enabled: rows stored
 * before they were switched off still expire. Several API instances each run it; the advisory lock inside the cleanup lets one
 * delete at a time. The interval, the first delay and the batch size are settings under
 * {@code listing-guard.image.retention}.
 */
@Configuration
@EnableScheduling
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "listing-guard.image.retention", name = "cleanup-enabled", havingValue = "true",
        matchIfMissing = true)
public class ImageCleanupSchedule implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ImageCleanupSchedule.class);

    private final ImageRetentionCleanup cleanup;
    private final ImageRetentionProperties properties;

    public ImageCleanupSchedule(ImageRetentionCleanup cleanup, ImageRetentionProperties properties) {
        this.cleanup = cleanup;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(this::run, properties.cleanupInterval(),
                properties.cleanupInitialDelay()));
    }

    private void run() {
        try {
            cleanup.runOnce();
        } catch (RuntimeException e) {
            // a failed run is tried again at the next interval; expiry is enforced on read in the meantime
            log.error("Image retention cleanup failed; it will run again after {}", properties.cleanupInterval(), e);
        }
    }
}
