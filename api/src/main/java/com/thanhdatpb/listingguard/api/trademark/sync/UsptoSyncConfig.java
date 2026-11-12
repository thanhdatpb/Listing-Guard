package com.thanhdatpb.listingguard.api.trademark.sync;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the settings of the {@code uspto-sync} job (ADR-009, PR A2d). */
@Configuration
@EnableConfigurationProperties(UsptoSyncProperties.class)
public class UsptoSyncConfig {
}
