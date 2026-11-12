package com.thanhdatpb.listingguard.api.trademark;

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
 * Makes the API serve a new activation without a restart (ADR-009, PR A2d): every
 * {@code listing-guard.trademark.activation.refresh-interval} it asks {@link TrademarkCatalogRuntime#refreshActivation()},
 * which reads the latest activation id and evaluates again only when it changed. It exists only in a web application
 * that follows the activation log; the one-shot jobs read the log once at start-up.
 */
@Configuration
@EnableScheduling
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "listing-guard.trademark.activation", name = "follow", havingValue = "true")
public class TrademarkActivationRefreshSchedule implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TrademarkActivationRefreshSchedule.class);

    private final TrademarkCatalogRuntime runtime;
    private final TrademarkActivationProperties properties;

    public TrademarkActivationRefreshSchedule(TrademarkCatalogRuntime runtime, TrademarkActivationProperties properties) {
        this.runtime = runtime;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(this::run, properties.refreshInterval(),
                properties.refreshInterval()));
    }

    private void run() {
        try {
            runtime.refreshActivation();
        } catch (RuntimeException e) {
            // the state it had stays in place; the next interval tries again
            log.error("Reading the trademark catalog activation log failed; it is read again after {}",
                    properties.refreshInterval(), e);
        }
    }
}
