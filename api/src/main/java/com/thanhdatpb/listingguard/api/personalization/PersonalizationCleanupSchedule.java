package com.thanhdatpb.listingguard.api.personalization;

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
 * Runs {@link PersonalizationRetentionCleanup} on a schedule in the API process (ADR-016, ADR-010). It exists only in a web
 * application, so the one-shot jobs (ingest, evaluation, admin, trademark-import, batch-check), which have no web server,
 * never start it. Several API instances each run it; the advisory lock inside the cleanup lets one delete at a time. The
 * interval, the first delay and the batch size are settings under {@code listing-guard.personalization.retention}.
 */
@Configuration
@EnableScheduling
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "listing-guard.personalization.retention", name = "cleanup-enabled", havingValue = "true",
        matchIfMissing = true)
public class PersonalizationCleanupSchedule implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PersonalizationCleanupSchedule.class);

    private final PersonalizationRetentionCleanup cleanup;
    private final PersonalizationRetentionProperties properties;

    public PersonalizationCleanupSchedule(PersonalizationRetentionCleanup cleanup,
                                          PersonalizationRetentionProperties properties) {
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
            log.error("Personalization retention cleanup failed; it will run again after {}", properties.cleanupInterval(), e);
        }
    }
}
