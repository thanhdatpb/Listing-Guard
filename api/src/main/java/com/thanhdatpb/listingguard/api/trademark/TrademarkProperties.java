package com.thanhdatpb.listingguard.api.trademark;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the trademark pack (ADR-009).
 *
 * @param ruleset        the trademark ruleset YAML
 * @param marketplaces   the marketplaces the ruleset is registered for (ADR-010: one {@code ruleset_version} row per
 *                       pack, marketplace and version)
 * @param catalogVersion the catalog version served for trademark checks, exactly as the import printed it. Empty
 *                       means trademark checks are off. There is no "latest": a newer import changes nothing until
 *                       this is changed.
 * @param catalogsDir    directory holding {@code manifest.yaml} and {@code raw/} for the import job
 */
@ConfigurationProperties(prefix = "listing-guard.trademark")
public record TrademarkProperties(
        @DefaultValue("classpath:rulesets/trademark-risk-2026-11.yaml") String ruleset,
        @DefaultValue({"ETSY", "AMAZON"}) List<String> marketplaces,
        @DefaultValue("") String catalogVersion,
        @DefaultValue("catalogs") String catalogsDir) {

    public TrademarkProperties {
        marketplaces = List.copyOf(marketplaces);
        catalogVersion = catalogVersion == null ? "" : catalogVersion.trim();
    }

    public boolean catalogConfigured() {
        return !catalogVersion.isEmpty();
    }
}
