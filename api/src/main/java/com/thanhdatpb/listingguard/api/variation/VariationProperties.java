package com.thanhdatpb.listingguard.api.variation;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the variation-checks pack (ADR-015).
 *
 * @param ruleset      the variation ruleset YAML
 * @param marketplaces the marketplaces the ruleset is registered for (ADR-010: one {@code ruleset_version} row per pack,
 *                     marketplace and version)
 */
@ConfigurationProperties(prefix = "listing-guard.variation")
public record VariationProperties(
        @DefaultValue("classpath:rulesets/variation-checks-2026-11.yaml") String ruleset,
        @DefaultValue({"ETSY", "AMAZON"}) List<String> marketplaces) {

    public VariationProperties {
        marketplaces = List.copyOf(marketplaces);
    }
}
