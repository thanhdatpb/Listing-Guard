package com.thanhdatpb.listingguard.personalization.config;

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
            throw new PersonalizationRulesetException("Ruleset must be a YAML mapping");
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
            throw new PersonalizationRulesetException(path() + " is missing required key '" + key + "'");
        }
        return value;
    }

    Section section(String key) {
        JsonNode value = require(key);
        if (!value.isObject()) {
            throw new PersonalizationRulesetException(where(key) + " must be a mapping");
        }
        return new Section(value, where(key) + ".");
    }

    /** A mapping whose every value is a mapping, in file order; the mapping must not be empty. */
    Map<String, Section> sections(String key) {
        JsonNode value = require(key);
        if (!value.isObject() || value.isEmpty()) {
            throw new PersonalizationRulesetException(where(key) + " must be a non-empty mapping");
        }
        Map<String, Section> result = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getKey().isBlank() || !entry.getValue().isObject()) {
                throw new PersonalizationRulesetException(where(key) + " entry '" + entry.getKey() + "' must be a mapping");
            }
            result.put(entry.getKey(), new Section(entry.getValue(), where(key) + "." + entry.getKey() + "."));
        }
        return result;
    }

    /** Every entry of this mapping as a section, in file order; each value must be a mapping, and there must be one. */
    Map<String, Section> children() {
        if (node.isEmpty()) {
            throw new PersonalizationRulesetException(path() + " must not be empty");
        }
        Map<String, Section> result = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            used.add(entry.getKey());
            if (entry.getKey().isBlank() || !entry.getValue().isObject()) {
                throw new PersonalizationRulesetException(path() + " entry '" + entry.getKey() + "' must be a mapping");
            }
            result.put(entry.getKey(), new Section(entry.getValue(), path + entry.getKey() + "."));
        }
        return result;
    }

    String text(String key) {
        JsonNode value = require(key);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new PersonalizationRulesetException(where(key) + " must be a non-empty string");
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
            throw new PersonalizationRulesetException(where(key) + " must be a string");
        }
        return value.asText();
    }

    /** A boolean. */
    boolean bool(String key) {
        JsonNode value = require(key);
        if (!value.isBoolean()) {
            throw new PersonalizationRulesetException(where(key) + " must be true or false");
        }
        return value.asBoolean();
    }

    /** True when the key is present (it counts as read). */
    boolean has(String key) {
        used.add(key);
        JsonNode value = node.get(key);
        return value != null && !value.isNull();
    }

    /** A mapping of non-empty strings to integers from {@code min} to {@code max}, in file order; empty when absent. */
    Map<String, Integer> optionalIntegerMap(String key, int min, int max) {
        used.add(key);
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return Map.of();
        }
        if (!value.isObject()) {
            throw new PersonalizationRulesetException(where(key) + " must be a mapping of integers");
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode item = entry.getValue();
            if (entry.getKey().isBlank() || !item.isIntegralNumber() || !item.canConvertToInt()
                    || item.asInt() < min || item.asInt() > max) {
                throw new PersonalizationRulesetException(where(key) + " entry '" + entry.getKey()
                        + "' must be an integer from " + min + " to " + max);
            }
            result.put(entry.getKey().trim(), item.asInt());
        }
        return result;
    }

    int integer(String key, int min, int max) {
        JsonNode value = require(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < min || value.asInt() > max) {
            throw new PersonalizationRulesetException(where(key) + " must be an integer from " + min + " to " + max
                    + ", got " + value);
        }
        return value.asInt();
    }

    /** A list of non-empty strings; {@code nonEmpty} also rejects an empty list. */
    List<String> strings(String key, boolean nonEmpty) {
        return stringsOf(key, require(key), nonEmpty);
    }

    /** Like {@link #strings}, or an empty list when the key is absent. */
    List<String> optionalStrings(String key) {
        used.add(key);
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return List.of();
        }
        return stringsOf(key, value, false);
    }

    private List<String> stringsOf(String key, JsonNode value, boolean nonEmpty) {
        if (!value.isArray() || (nonEmpty && value.isEmpty())) {
            throw new PersonalizationRulesetException(where(key) + " must be a " + (nonEmpty ? "non-empty " : "")
                    + "list of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new PersonalizationRulesetException(where(key) + " must contain only non-empty strings, got " + item);
            }
            result.add(item.asText().trim());
        }
        return result;
    }

    /** A non-empty list of non-empty lists of strings. */
    List<List<String>> stringLists(String key) {
        JsonNode value = require(key);
        if (!value.isArray() || value.isEmpty()) {
            throw new PersonalizationRulesetException(where(key) + " must be a non-empty list of lists of strings");
        }
        List<List<String>> result = new ArrayList<>();
        for (JsonNode item : value) {
            result.add(stringsOf(key, item, true));
        }
        return result;
    }

    /** A non-empty mapping from a name to a non-empty list of strings, in file order. */
    Map<String, List<String>> stringListMap(String key) {
        JsonNode value = require(key);
        if (!value.isObject() || value.isEmpty()) {
            throw new PersonalizationRulesetException(where(key) + " must be a non-empty mapping of lists of strings");
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            result.put(entry.getKey(), stringsOf(key + "." + entry.getKey(), entry.getValue(), true));
        }
        return result;
    }

    /** A mapping of non-empty strings to non-empty strings, in file order; must not be empty. */
    Map<String, String> stringMap(String key) {
        JsonNode value = require(key);
        if (!value.isObject() || value.isEmpty()) {
            throw new PersonalizationRulesetException(where(key) + " must be a non-empty mapping");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getKey().isBlank() || !entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                throw new PersonalizationRulesetException(where(key) + " must map non-empty strings to non-empty strings; "
                        + "bad entry '" + entry.getKey() + "'");
            }
            result.put(entry.getKey().trim(), entry.getValue().asText().trim());
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
                throw new PersonalizationRulesetException("Unknown key '" + name + "' in " + path());
            }
        }
    }
}
