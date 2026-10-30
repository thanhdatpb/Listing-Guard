package com.thanhdatpb.listingguard.api.trademark;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TrademarkProperties.class, TrademarkRetentionProperties.class})
public class TrademarkConfig {
}
