package com.thanhdatpb.listingguard.api.variation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({VariationProperties.class, VariationRetentionProperties.class})
public class VariationConfig {
}
