package com.thanhdatpb.listingguard.ruleengine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Rules grouped by marketplace code, in registration order. */
public final class RuleRegistry {

    private final Map<String, List<Rule>> byMarketplace = new LinkedHashMap<>();

    public RuleRegistry register(String marketplace, Rule rule) {
        List<Rule> rules = byMarketplace.computeIfAbsent(key(marketplace), k -> new ArrayList<>());
        if (rules.stream().anyMatch(existing -> existing.id().equals(rule.id()))) {
            throw new IllegalArgumentException("Duplicate rule id '" + rule.id() + "' for marketplace " + marketplace);
        }
        rules.add(rule);
        return this;
    }

    /** @throws IllegalArgumentException if no rules are registered for the marketplace */
    public List<Rule> rulesFor(String marketplace) {
        List<Rule> rules = byMarketplace.get(key(marketplace));
        if (rules == null) {
            throw new IllegalArgumentException("No rules registered for marketplace " + marketplace);
        }
        return List.copyOf(rules);
    }

    private static String key(String marketplace) {
        return marketplace.trim().toUpperCase(Locale.ROOT);
    }
}
