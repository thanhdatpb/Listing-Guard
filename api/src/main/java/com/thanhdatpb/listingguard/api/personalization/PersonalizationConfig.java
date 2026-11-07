package com.thanhdatpb.listingguard.api.personalization;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PersonalizationProperties.class, PersonalizationRetentionProperties.class})
public class PersonalizationConfig {
}
