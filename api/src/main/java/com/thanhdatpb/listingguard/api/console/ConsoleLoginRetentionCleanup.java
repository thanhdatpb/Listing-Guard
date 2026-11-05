package com.thanhdatpb.listingguard.api.console;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleLoginRetentionCleanup {

    private final JdbcTemplate jdbc;
    private final ConsoleProperties properties;
    private final Clock clock;

    public ConsoleLoginRetentionCleanup(JdbcTemplate jdbc, ConsoleProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public void runOnce() {
        Instant now = clock.instant();
        jdbc.update("DELETE FROM login_token WHERE expires_at <= ?", Timestamp.from(now));
        long windowSeconds = properties.rateWindow().toSeconds();
        Instant expiredWindow = now.minus(Duration.ofSeconds(windowSeconds * 2));
        jdbc.update("DELETE FROM console_login_rate_limit WHERE window_start < ?", Timestamp.from(expiredWindow));
    }
}
