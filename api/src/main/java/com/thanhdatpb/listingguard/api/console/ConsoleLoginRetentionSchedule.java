package com.thanhdatpb.listingguard.api.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleLoginRetentionSchedule {

    private static final Logger log = LoggerFactory.getLogger(ConsoleLoginRetentionSchedule.class);

    private final ConsoleLoginRetentionCleanup cleanup;

    public ConsoleLoginRetentionSchedule(ConsoleLoginRetentionCleanup cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void cleanupExpiredLoginData() {
        try {
            cleanup.runOnce();
        } catch (RuntimeException exception) {
            log.error("Console login-token retention cleanup failed; it will retry after one hour.", exception);
        }
    }
}
