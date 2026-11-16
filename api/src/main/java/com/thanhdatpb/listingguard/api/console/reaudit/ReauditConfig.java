package com.thanhdatpb.listingguard.api.console.reaudit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ReauditProperties}. The store is always there, because the health report (ADR-018) counts runs whether or not the
 * console is on.
 */
@Configuration
@EnableConfigurationProperties(ReauditProperties.class)
public class ReauditConfig {
}
