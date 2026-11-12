package com.thanhdatpb.listingguard.api.trademark.tsdr;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the settings of the TSDR status check (ADR-009, "TSDR status check"). */
@Configuration
@EnableConfigurationProperties(TsdrProperties.class)
public class TsdrConfig {
}
