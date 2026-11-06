package com.thanhdatpb.listingguard.api.personalization;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the personalization-screening pack (ADR-016).
 *
 * @param ruleset      the personalization ruleset YAML
 * @param marketplaces the marketplaces the ruleset is registered for (ADR-010: one {@code ruleset_version} row per pack,
 *                     marketplace and version)
 */
@ConfigurationProperties(prefix = "listing-guard.personalization")
public record PersonalizationProperties(
        @DefaultValue("classpath:rulesets/personalization-screening-2026-11.yaml") String ruleset,
        @DefaultValue({"ETSY", "AMAZON"}) List<String> marketplaces) {

    public PersonalizationProperties {
        marketplaces = List.copyOf(marketplaces);
    }
}
