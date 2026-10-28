package com.thanhdatpb.listingguard.ruleengine.config;

import com.thanhdatpb.listingguard.core.finding.Severity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration of one rule: severity, documentation source and free-form typed parameters.
 *
 * <p>{@code enabled} switches the rule off without removing it, so its configuration is still validated and its
 * source stays on record. {@code categoryOverrides} holds, per category (an Amazon product type), the complete
 * settings that apply to listings of that category: the defaults with the overridden keys replaced. An override
 * carries its own source. The keys are upper case.
 */
public record RuleSettings(String ruleId, Severity severity, Source source, Map<String, Object> params,
                           boolean enabled, Map<String, RuleSettings> categoryOverrides) {

    public RuleSettings {
        params = params == null ? Map.of() : Map.copyOf(params);
        categoryOverrides = categoryOverrides == null ? Map.of() : Map.copyOf(categoryOverrides);
    }

    /** Settings with no category overrides, enabled. */
    public RuleSettings(String ruleId, Severity severity, Source source, Map<String, Object> params) {
        this(ruleId, severity, source, params, true, Map.of());
    }

    /**
     * The settings that apply to a listing of {@code category}: the override when the ruleset has one for it,
     * otherwise these (the defaults). The match ignores case and surrounding blanks; null means no category.
     */
    public RuleSettings forCategory(String category) {
        if (category == null) {
            return this;
        }
        return categoryOverrides.getOrDefault(category.trim().toUpperCase(Locale.ROOT), this);
    }

    /** These settings followed by every category override, so a check can validate all of them. */
    public List<RuleSettings> withOverrides() {
        List<RuleSettings> all = new ArrayList<>();
        all.add(this);
        all.addAll(categoryOverrides.values());
        return all;
    }

    public int intParam(String name) {
        Object value = require(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw invalid(name, "an integer", value);
    }

    public BigDecimal decimalParam(String name) {
        Object value = require(name);
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw invalid(name, "a number", value);
    }

    public String stringParam(String name) {
        Object value = require(name);
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        throw invalid(name, "a non-empty string", value);
    }

    public boolean boolParam(String name) {
        Object value = require(name);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw invalid(name, "a boolean", value);
    }

    public List<String> stringListParam(String name) {
        Object value = require(name);
        if (value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String)) {
            return list.stream().map(String.class::cast).toList();
        }
        throw invalid(name, "a list of strings", value);
    }

    /** A mapping from names to non-empty strings, in the order of the YAML file. */
    public Map<String, String> stringMapParam(String name) {
        Object value = require(name);
        if (value instanceof Map<?, ?> map && !map.isEmpty()
                && map.entrySet().stream().allMatch(e -> e.getKey() instanceof String
                && e.getValue() instanceof String text && !text.isBlank())) {
            Map<String, String> copy = new LinkedHashMap<>();
            map.forEach((key, text) -> copy.put((String) key, (String) text));
            return copy;
        }
        throw invalid(name, "a non-empty mapping of names to non-empty strings", value);
    }

    private Object require(String name) {
        Object value = params.get(name);
        if (value == null) {
            throw new RulesetException("Rule '" + ruleId + "' is missing required key '" + name + "'");
        }
        return value;
    }

    private RulesetException invalid(String name, String expected, Object actual) {
        return new RulesetException("Rule '" + ruleId + "': key '" + name + "' must be " + expected + ", got " + actual);
    }
}
