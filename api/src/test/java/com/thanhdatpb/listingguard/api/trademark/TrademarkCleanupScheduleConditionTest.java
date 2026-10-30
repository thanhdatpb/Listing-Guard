package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * The scheduled cleanup belongs to the API process only: the one-shot jobs have no web server and never start it, and it
 * can be switched off by setting.
 */
class TrademarkCleanupScheduleConditionTest {

    private static final TrademarkRetentionProperties PROPERTIES = new TrademarkRetentionProperties(Duration.ofDays(30), true,
            Duration.ofHours(1), Duration.ofMinutes(1), 500);

    @Test
    void aWebApplicationSchedulesTheCleanup() {
        new WebApplicationContextRunner().withUserConfiguration(TrademarkCleanupSchedule.class)
                .withBean(TrademarkRetentionProperties.class, () -> PROPERTIES)
                .withBean(TrademarkRetentionCleanup.class, () -> mock(TrademarkRetentionCleanup.class))
                .run(context -> assertThat(context).hasSingleBean(TrademarkCleanupSchedule.class));
    }

    @Test
    void anApplicationWithoutAWebServerDoesNot() {
        new ApplicationContextRunner().withUserConfiguration(TrademarkCleanupSchedule.class)
                .withBean(TrademarkRetentionProperties.class, () -> PROPERTIES)
                .withBean(TrademarkRetentionCleanup.class, () -> mock(TrademarkRetentionCleanup.class))
                .run(context -> assertThat(context).doesNotHaveBean(TrademarkCleanupSchedule.class));
    }

    @Test
    void theSettingTurnsItOff() {
        new WebApplicationContextRunner().withUserConfiguration(TrademarkCleanupSchedule.class)
                .withPropertyValues("listing-guard.trademark.retention.cleanup-enabled=false")
                .withBean(TrademarkRetentionProperties.class, () -> PROPERTIES)
                .withBean(TrademarkRetentionCleanup.class, () -> mock(TrademarkRetentionCleanup.class))
                .run(context -> assertThat(context).doesNotHaveBean(TrademarkCleanupSchedule.class));
    }

    @Test
    void theSettingsAreValidatedAtStartUp() {
        assertThatThrownBy(() -> new TrademarkRetentionProperties(Duration.ZERO, true, Duration.ofHours(1),
                Duration.ofMinutes(1), 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrademarkRetentionProperties(Duration.ofDays(30), true, Duration.ZERO,
                Duration.ofMinutes(1), 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrademarkRetentionProperties(Duration.ofDays(30), true, Duration.ofHours(1),
                Duration.ofMinutes(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
