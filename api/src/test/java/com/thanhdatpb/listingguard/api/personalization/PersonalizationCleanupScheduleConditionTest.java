package com.thanhdatpb.listingguard.api.personalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** The cleanup is scheduled in a web application only, and the settings are validated at start-up (ADR-016). */
class PersonalizationCleanupScheduleConditionTest {

    private static final PersonalizationRetentionProperties PROPERTIES = new PersonalizationRetentionProperties(
            Duration.ofDays(30), true, Duration.ofHours(1), Duration.ofMinutes(3), 500);

    @Test
    void aWebApplicationSchedulesTheCleanup() {
        new WebApplicationContextRunner().withUserConfiguration(PersonalizationCleanupSchedule.class)
                .withBean(PersonalizationRetentionProperties.class, () -> PROPERTIES)
                .withBean(PersonalizationRetentionCleanup.class, () -> mock(PersonalizationRetentionCleanup.class))
                .run(context -> assertThat(context).hasSingleBean(PersonalizationCleanupSchedule.class));
    }

    @Test
    void anApplicationWithoutAWebServerDoesNot() {
        new ApplicationContextRunner().withUserConfiguration(PersonalizationCleanupSchedule.class)
                .withBean(PersonalizationRetentionProperties.class, () -> PROPERTIES)
                .withBean(PersonalizationRetentionCleanup.class, () -> mock(PersonalizationRetentionCleanup.class))
                .run(context -> assertThat(context).doesNotHaveBean(PersonalizationCleanupSchedule.class));
    }

    @Test
    void theSettingTurnsItOff() {
        new WebApplicationContextRunner().withUserConfiguration(PersonalizationCleanupSchedule.class)
                .withPropertyValues("listing-guard.personalization.retention.cleanup-enabled=false")
                .withBean(PersonalizationRetentionProperties.class, () -> PROPERTIES)
                .withBean(PersonalizationRetentionCleanup.class, () -> mock(PersonalizationRetentionCleanup.class))
                .run(context -> assertThat(context).doesNotHaveBean(PersonalizationCleanupSchedule.class));
    }

    @Test
    void theSettingsAreValidatedAtStartUp() {
        assertThatThrownBy(() -> new PersonalizationRetentionProperties(Duration.ZERO, true, Duration.ofHours(1),
                Duration.ofMinutes(3), 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonalizationRetentionProperties(Duration.ofDays(30), true, Duration.ZERO,
                Duration.ofMinutes(3), 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonalizationRetentionProperties(Duration.ofDays(30), true, Duration.ofHours(1),
                Duration.ofMinutes(3), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonalizationRetentionProperties(Duration.ofDays(30), true, Duration.ofHours(1),
                Duration.ofMinutes(-1), 500)).isInstanceOf(IllegalArgumentException.class);
    }
}
