package com.thanhdatpb.listingguard.api.console.bulk;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link BulkProperties} always: the request body limit reads them whether or not the console is on. */
@Configuration
@EnableConfigurationProperties(BulkProperties.class)
public class BulkConfig {
}
