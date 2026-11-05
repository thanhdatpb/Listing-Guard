package com.thanhdatpb.listingguard.api.ruleset;

import com.thanhdatpb.listingguard.api.check.MarketplaceProperties;
import com.thanhdatpb.listingguard.api.check.FixesProperties;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.readiness.ReadinessLoader;
import com.thanhdatpb.listingguard.ruleengine.readiness.ReadinessScorer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({RulesetProperties.class, MarketplaceProperties.class, FixesProperties.class})
public class RuleEngineConfig {

    @Bean
    public RuleEngine ruleEngine() {
        return new RuleEngine(RuleRegistry.withDefaults());
    }

    @Bean
    public ReadinessScorer readinessScorer() {
        return new ReadinessScorer(ReadinessLoader.loadClasspath("readiness/readiness-2026-11.yaml"));
    }
}
