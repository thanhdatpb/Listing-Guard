package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.ruleengine.amazon.AmazonRules;
import com.thanhdatpb.listingguard.ruleengine.etsy.EtsyRules;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Rules grouped by marketplace code, in registration order. */
public final class RuleRegistry {

    private final Map<Identity, List<Rule>> byPackAndMarketplace = new LinkedHashMap<>();

    /** A registry holding the built-in rules of every supported marketplace. */
    public static RuleRegistry withDefaults() {
        RuleRegistry registry = new RuleRegistry();
        EtsyRules.all().forEach(rule -> registry.register(RulesetConfig.DEFAULT_PACK_ID, EtsyRules.MARKETPLACE, rule));
        AmazonRules.all().forEach(rule -> registry.register(RulesetConfig.DEFAULT_PACK_ID, AmazonRules.MARKETPLACE, rule));
        return registry;
    }

    public RuleRegistry register(String marketplace, Rule rule) {
        return register(RulesetConfig.DEFAULT_PACK_ID, marketplace, rule);
    }

    public RuleRegistry register(String packId, String marketplace, Rule rule) {
        List<Rule> rules = byPackAndMarketplace.computeIfAbsent(key(packId, marketplace), k -> new ArrayList<>());
        if (rules.stream().anyMatch(existing -> existing.id().equals(rule.id()))) {
            throw new IllegalArgumentException("Duplicate rule id '" + rule.id() + "' for pack " + packId
                    + " and marketplace " + marketplace);
        }
        rules.add(rule);
        return this;
    }

    /** @throws IllegalArgumentException if no rules are registered for the marketplace */
    public List<Rule> rulesFor(String marketplace) {
        return rulesFor(RulesetConfig.DEFAULT_PACK_ID, marketplace);
    }

    /** @throws IllegalArgumentException if no rules are registered for the pack and marketplace */
    public List<Rule> rulesFor(String packId, String marketplace) {
        List<Rule> rules = byPackAndMarketplace.get(key(packId, marketplace));
        if (rules == null) {
            throw new IllegalArgumentException("No rules registered for pack " + packId + " and marketplace " + marketplace);
        }
        return List.copyOf(rules);
    }

    private static Identity key(String packId, String marketplace) {
        String normalizedPackId = packId.trim().toLowerCase(Locale.ROOT);
        if (normalizedPackId.isEmpty()) {
            throw new IllegalArgumentException("Pack id must not be blank");
        }
        return new Identity(normalizedPackId, marketplace.trim().toUpperCase(Locale.ROOT));
    }

    private record Identity(String packId, String marketplace) {
    }
}
