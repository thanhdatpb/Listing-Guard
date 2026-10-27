package com.thanhdatpb.listingguard.api.ruleset;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Ruleset YAML files to load at startup, e.g. {@code classpath:rulesets/etsy-2026-10.yaml}. */
@ConfigurationProperties(prefix = "listing-guard.rulesets")
public record RulesetProperties(List<String> locations) {

    public RulesetProperties {
        locations = locations == null ? List.of() : List.copyOf(locations);
    }
}
