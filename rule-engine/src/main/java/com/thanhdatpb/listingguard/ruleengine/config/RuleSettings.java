package com.thanhdatpb.listingguard.ruleengine.config;

import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import java.util.Map;

/** Configuration of one rule: severity, documentation source and free-form typed parameters. */
public record RuleSettings(String ruleId, Severity severity, Source source, Map<String, Object> params) {

    public RuleSettings {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public int intParam(String name) {
        Object value = require(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw invalid(name, "an integer", value);
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
