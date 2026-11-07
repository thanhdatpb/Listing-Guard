package com.thanhdatpb.listingguard.api.report;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ReportsProperties.class, HealthReportJobProperties.class})
public class ReportsConfig {
}
