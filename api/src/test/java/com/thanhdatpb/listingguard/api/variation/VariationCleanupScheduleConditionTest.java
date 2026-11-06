package com.thanhdatpb.listingguard.api.variation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * The scheduled cleanup belongs to the API process only: the one-shot jobs have no web server and never start it, and it can
 * be switched off by setting.
 */
class VariationCleanupScheduleConditionTest {

    private static final VariationRetentionProperties PROPERTIES = new VariationRetentionProperties(Duration.ofDays(30), true,
            Duration.ofHours(1), Duration.ofMinutes(2), 500);

    @Test
    void aWebApplicationSchedulesTheCleanup() {
        new WebApplicationContextRunner().withUserConfiguration(VariationCleanupSchedule.class)
                .withBean(VariationRetentionProperties.class, () -> PROPERTIES)
                .withBean(VariationRetentionCleanup.class, () -> mock(VariationRetentionCleanup.class))
                .run(context -> assertThat(context).hasSingleBean(VariationCleanupSchedule.class));
    }

    @Test
    void anApplicationWithoutAWebServerDoesNot() {
        new ApplicationContextRunner().withUserConfiguration(VariationCleanupSchedule.class)
                .withBean(VariationRetentionProperties.class, () -> PROPERTIES)
                .withBean(VariationRetentionCleanup.class, () -> mock(VariationRetentionCleanup.class))
                .run(context -> assertThat(context).doesNotHaveBean(VariationCleanupSchedule.class));
    }

    @Test
    void theSettingTurnsItOff() {
        new WebApplicationContextRunner().withUserConfiguration(VariationCleanupSchedule.class)
                .withPropertyValues("listing-guard.variation.retention.cleanup-enabled=false")
                .withBean(VariationRetentionProperties.class, () -> PROPERTIES)
                .withBean(VariationRetentionCleanup.class, () -> mock(VariationRetentionCleanup.class))
                .run(context -> assertThat(context).doesNotHaveBean(VariationCleanupSchedule.class));
    }

    @Test
    void theSettingsAreValidatedAtStartUpAndHaveTheDocumentedDefaults() {
        assertThatThrownBy(() -> new VariationRetentionProperties(Duration.ZERO, true, Duration.ofHours(1),
                Duration.ofMinutes(2), 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VariationRetentionProperties(Duration.ofDays(30), true, Duration.ZERO,
                Duration.ofMinutes(2), 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VariationRetentionProperties(Duration.ofDays(30), true, Duration.ofHours(1),
                Duration.ofMinutes(2), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VariationRetentionProperties(Duration.ofDays(30), true, Duration.ofHours(1),
                Duration.ofMinutes(-1), 500)).isInstanceOf(IllegalArgumentException.class);
    }
}
