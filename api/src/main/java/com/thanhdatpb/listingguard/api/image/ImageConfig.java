package com.thanhdatpb.listingguard.api.image;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ImageProperties.class, ImageRetentionProperties.class})
public class ImageConfig {
}
