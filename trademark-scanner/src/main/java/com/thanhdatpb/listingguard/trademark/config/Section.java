package com.thanhdatpb.listingguard.trademark.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A YAML mapping read strictly: every key must be asked for, and {@link #finish()} fails when the file has a key
 * nobody asked for. Together with the required getters this makes a typo or a missing key a load-time error.
 */
final class Section {

    private final JsonNode node;
    private final String path;
    private final Set<String> used = new LinkedHashSet<>();

    private Section(JsonNode node, String path) {
        this.node = node;
        this.path = path;
    }

    static Section root(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new TrademarkRulesetException("Ruleset must be a YAML mapping");
        }
        return new Section(node, "");
    }

    String path() {
        return path.isEmpty() ? "ruleset" : path.substring(0, path.length() - 1);
    }

    private String where(String key) {
        return path + key;
    }

    private JsonNode require(String key) {
        used.add(key);
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            throw new TrademarkRulesetException(path() + " is missing required key '" + key + "'");
        }
        return value;
    }

    Section section(String key) {
        JsonNode value = require(key);
        if (!value.isObject()) {
            throw new TrademarkRulesetException(where(key) + " must be a mapping");
        }
        return new Section(value, where(key) + ".");
    }

    String text(String key) {
        JsonNode value = require(key);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new TrademarkRulesetException(where(key) + " must be a non-empty string");
        }
        return value.asText().trim();
    }

    /** A string, or null when the key is absent. */
    String optionalText(String key) {
        used.add(key);
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new TrademarkRulesetException(where(key) + " must be a string");
        }
        return value.asText();
    }

    int integer(String key, int min, int max) {
        JsonNode value = require(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < min || value.asInt() > max) {
            throw new TrademarkRulesetException(where(key) + " must be an integer from " + min + " to " + max
                    + ", got " + value);
        }
        return value.asInt();
    }

    /** A number above 0 and at most 1. */
    double fraction(String key) {
        JsonNode value = require(key);
        if (!value.isNumber() || value.asDouble() <= 0 || value.asDouble() > 1) {
            throw new TrademarkRulesetException(where(key) + " must be a number above 0 and at most 1, got " + value);
        }
        return value.decimalValue().doubleValue();
    }

    /** A list of non-empty strings; {@code nonEmpty} also rejects an empty list. */
    List<String> strings(String key, boolean nonEmpty) {
        JsonNode value = require(key);
        if (!value.isArray() || (nonEmpty && value.isEmpty())) {
            throw new TrademarkRulesetException(where(key) + " must be a " + (nonEmpty ? "non-empty " : "")
                    + "list of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isEmpty()) {
                throw new TrademarkRulesetException(where(key) + " must contain only non-empty strings, got " + item);
            }
            result.add(item.asText());
        }
        return result;
    }

    /** A mapping of non-empty strings to non-empty strings, in file order. May be empty. */
    Map<String, String> stringMap(String key) {
        JsonNode value = require(key);
        if (!value.isObject()) {
            throw new TrademarkRulesetException(where(key) + " must be a mapping");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getKey().isEmpty() || !entry.getValue().isTextual() || entry.getValue().asText().isEmpty()) {
                throw new TrademarkRulesetException(where(key) + " must map non-empty strings to non-empty strings; "
                        + "bad entry '" + entry.getKey() + "'");
            }
            result.put(entry.getKey(), entry.getValue().asText());
        }
        return result;
    }

    /** The keys in file order. Does not count as reading them. */
    List<String> keys() {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** Fails on any key that was never read. */
    void finish() {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!used.contains(name)) {
                throw new TrademarkRulesetException("Unknown key '" + name + "' in " + path());
            }
        }
    }
}
