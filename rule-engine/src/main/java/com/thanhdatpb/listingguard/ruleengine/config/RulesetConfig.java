package com.thanhdatpb.listingguard.ruleengine.config;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A parsed ruleset: all thresholds, word lists and severities of one marketplace at one version.
 * {@code contentHash} is the SHA-256 of the normalized YAML text it was parsed from.
 */
public record RulesetConfig(
        String marketplace,
        String version,
        LocalDate checkedAt,
        String contentHash,
        Map<String, RuleSettings> rules) {

    public RulesetConfig {
        rules = Map.copyOf(rules);
    }

    public RuleSettings rule(String ruleId) {
        RuleSettings settings = rules.get(ruleId);
        if (settings == null) {
            throw new RulesetException("Ruleset " + version + " has no configuration for rule '" + ruleId + "'");
        }
        return settings;
    }

    /** Distinct documentation URLs referenced by this ruleset, sorted. */
    public List<String> sourceUrls() {
        return rules.values().stream()
                .flatMap(settings -> settings.source().urls().stream())
                .distinct()
                .sorted()
                .toList();
    }
}
