package com.thanhdatpb.listingguard.ruleengine.config;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A parsed ruleset: all thresholds, word lists and severities of one marketplace at one version.
 * {@code contentHash} is the SHA-256 of the normalized YAML text it was parsed from.
 */
public record RulesetConfig(
        String packId,
        String marketplace,
        String version,
        LocalDate checkedAt,
        String contentHash,
        Map<String, RuleSettings> rules) {

    public static final String DEFAULT_PACK_ID = "listing-rules";

    public RulesetConfig {
        packId = packId == null ? DEFAULT_PACK_ID : packId;
        rules = Map.copyOf(rules);
    }

    public RulesetConfig(String marketplace, String version, LocalDate checkedAt, String contentHash,
                         Map<String, RuleSettings> rules) {
        this(DEFAULT_PACK_ID, marketplace, version, checkedAt, contentHash, rules);
    }

    /** The default settings of a rule. */
    public RuleSettings rule(String ruleId) {
        RuleSettings settings = rules.get(ruleId);
        if (settings == null) {
            throw new RulesetException("Ruleset " + version + " has no configuration for rule '" + ruleId + "'");
        }
        return settings;
    }

    /** The settings of a rule for a listing of {@code category}: the category's override, else the defaults. */
    public RuleSettings rule(String ruleId, String category) {
        return rule(ruleId).forCategory(category);
    }

    /** Every category that at least one rule overrides (upper case, sorted). */
    public Set<String> categories() {
        Set<String> categories = new TreeSet<>();
        rules.values().forEach(settings -> categories.addAll(settings.categoryOverrides().keySet()));
        return categories;
    }

    /** True when some rule has an override for the category; a listing of another category gets the defaults only. */
    public boolean coversCategory(String category) {
        return category != null && categories().contains(category.trim().toUpperCase(Locale.ROOT));
    }

    /** Distinct documentation URLs referenced by this ruleset, defaults and overrides, sorted. */
    public List<String> sourceUrls() {
        return rules.values().stream()
                .flatMap(settings -> settings.withOverrides().stream())
                .flatMap(settings -> settings.source().urls().stream())
                .distinct()
                .sorted()
                .toList();
    }
}
