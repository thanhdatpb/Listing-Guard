package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * The refresh of the activation log belongs to an API that follows the log: not to the one-shot jobs, and not to an API that
 * serves the configured catalog version (ADR-009, PR A2d).
 */
class TrademarkActivationRefreshScheduleConditionTest {

    private static final TrademarkActivationProperties FOLLOW = new TrademarkActivationProperties(true, Duration.ofMinutes(1));

    @Test
    void aWebApplicationThatFollowsTheLogRefreshesIt() {
        new WebApplicationContextRunner().withUserConfiguration(TrademarkActivationRefreshSchedule.class)
                .withPropertyValues("listing-guard.trademark.activation.follow=true")
                .withBean(TrademarkActivationProperties.class, () -> FOLLOW)
                .withBean(TrademarkCatalogRuntime.class, () -> mock(TrademarkCatalogRuntime.class))
                .run(context -> assertThat(context).hasSingleBean(TrademarkActivationRefreshSchedule.class));
    }

    @Test
    void notFollowingTheLogMeansNoRefresh() {
        new WebApplicationContextRunner().withUserConfiguration(TrademarkActivationRefreshSchedule.class)
                .withBean(TrademarkActivationProperties.class, TrademarkActivationProperties::off)
                .withBean(TrademarkCatalogRuntime.class, () -> mock(TrademarkCatalogRuntime.class))
                .run(context -> assertThat(context).doesNotHaveBean(TrademarkActivationRefreshSchedule.class));
    }

    @Test
    void aJobWithoutAWebServerDoesNotRefresh() {
        new ApplicationContextRunner().withUserConfiguration(TrademarkActivationRefreshSchedule.class)
                .withPropertyValues("listing-guard.trademark.activation.follow=true")
                .withBean(TrademarkActivationProperties.class, () -> FOLLOW)
                .withBean(TrademarkCatalogRuntime.class, () -> mock(TrademarkCatalogRuntime.class))
                .run(context -> assertThat(context).doesNotHaveBean(TrademarkActivationRefreshSchedule.class));
    }
}
