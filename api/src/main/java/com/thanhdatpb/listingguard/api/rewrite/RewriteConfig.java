package com.thanhdatpb.listingguard.api.rewrite;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link RewriteProperties} only when the feature is switched on (ADR-037). */
@Configuration
@ConditionalOnProperty(prefix = "listing-guard.llm.rewrite", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RewriteProperties.class)
public class RewriteConfig {
}
