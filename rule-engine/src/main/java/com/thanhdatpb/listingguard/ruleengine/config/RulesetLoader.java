package com.thanhdatpb.listingguard.ruleengine.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses a ruleset YAML into a {@link RulesetConfig}. Strict on purpose: unknown top-level keys,
 * duplicate keys, bad severities and sourceless "verified" claims all fail here, at load time.
 *
 * <p>A rule may switch itself off with {@code enabled: false} and may carry {@code categories}: per category (an
 * Amazon product type) an override of some of its keys. An override must declare its own {@code source}, may only
 * replace keys that the rule already defines, and cannot nest further. The merged settings are built here, so
 * the rules see one flat set of settings per category.
 *
 * <p>The content hash is computed over the text after line endings are normalized to LF and a
 * leading BOM is dropped, so the same file hashes the same on Windows and on Linux CI.
 */
public final class RulesetLoader {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("marketplace", "version", "checked_at", "rules");
    private static final Set<String> RESERVED_RULE_KEYS = Set.of("severity", "source", "enabled", "categories");
    private static final Set<String> OVERRIDE_CONTROL_KEYS = Set.of("severity", "source", "enabled");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private RulesetLoader() {
    }

    public static RulesetConfig loadClasspath(String path) {
        String resource = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = RulesetLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new RulesetException("Ruleset not found on classpath: " + path);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RulesetException("Cannot read ruleset " + path, e);
        }
    }

    public static RulesetConfig parse(String yamlText) {
        String text = normalize(yamlText);
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new RulesetException("Invalid ruleset YAML: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new RulesetException("Ruleset must be a YAML mapping");
        }
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!TOP_LEVEL_KEYS.contains(name)) {
                throw new RulesetException("Unknown top-level key '" + name + "'");
            }
        }

        String marketplace = requiredText(root, "marketplace", "ruleset").toUpperCase(Locale.ROOT);
        String version = requiredText(root, "version", "ruleset");
        LocalDate checkedAt = parseDate(requiredText(root, "checked_at", "ruleset"));
        return new RulesetConfig(marketplace, version, checkedAt, sha256(text), parseRules(root.get("rules")));
    }

    /** LF line endings, no BOM. This is the text that gets hashed. */
    static String normalize(String text) {
        String withoutBom = text.startsWith("﻿") ? text.substring(1) : text;
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Map<String, RuleSettings> parseRules(JsonNode rulesNode) {
        if (rulesNode == null || !rulesNode.isObject() || rulesNode.isEmpty()) {
            throw new RulesetException("Ruleset must define at least one rule under 'rules'");
        }
        Map<String, RuleSettings> rules = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = rulesNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            rules.put(entry.getKey(), parseRule(entry.getKey(), entry.getValue()));
        }
        return rules;
    }

    private static RuleSettings parseRule(String id, JsonNode node) {
        if (!node.isObject()) {
            throw new RulesetException("Rule '" + id + "' must be a mapping");
        }
        String label = "Rule '" + id + "'";
        Severity severity = parseSeverity(label, requiredText(node, "severity", "rule '" + id + "'"));
        Source source = parseSource(label, node.get("source"));
        boolean enabled = parseEnabled(label, node.get("enabled"), true);

        Map<String, Object> params = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            if (!RESERVED_RULE_KEYS.contains(field.getKey())) {
                params.put(field.getKey(), YAML.convertValue(field.getValue(), Object.class));
            }
        }
        Map<String, RuleSettings> overrides = parseCategories(id, node.get("categories"), severity, enabled, params);
        return new RuleSettings(id, severity, source, params, enabled, overrides);
    }

    /** Builds the complete settings of every category override, merged over the rule's defaults. */
    private static Map<String, RuleSettings> parseCategories(String ruleId, JsonNode categories, Severity severity,
                                                            boolean enabled, Map<String, Object> defaults) {
        if (categories == null) {
            return Map.of();
        }
        if (!categories.isObject() || categories.isEmpty()) {
            throw new RulesetException("Rule '" + ruleId + "': 'categories' must be a mapping of category to overrides");
        }
        Map<String, RuleSettings> result = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = categories.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String category = entry.getKey().trim().toUpperCase(Locale.ROOT);
            String label = "Rule '" + ruleId + "' category '" + entry.getKey() + "'";
            if (category.isEmpty()) {
                throw new RulesetException("Rule '" + ruleId + "': a category name is empty");
            }
            if (result.containsKey(category)) {
                throw new RulesetException(label + " is declared twice (category names ignore case)");
            }
            JsonNode override = entry.getValue();
            if (!override.isObject()) {
                throw new RulesetException(label + " must be a mapping");
            }
            Source source = parseSource(label, override.get("source"));
            Severity overrideSeverity = override.has("severity")
                    ? parseSeverity(label, requiredText(override, "severity", label)) : severity;
            boolean overrideEnabled = parseEnabled(label, override.get("enabled"), enabled);

            Map<String, Object> merged = new LinkedHashMap<>(defaults);
            for (Iterator<Map.Entry<String, JsonNode>> fields = override.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if (OVERRIDE_CONTROL_KEYS.contains(key)) {
                    continue;
                }
                if (key.equals("categories")) {
                    throw new RulesetException(label + " cannot contain 'categories'");
                }
                if (!defaults.containsKey(key)) {
                    throw new RulesetException(label + " overrides '" + key + "', which the rule does not define; "
                            + "an override can only replace a key that exists in the rule's defaults");
                }
                merged.put(key, YAML.convertValue(field.getValue(), Object.class));
            }
            result.put(category, new RuleSettings(ruleId, overrideSeverity, source, merged, overrideEnabled, Map.of()));
        }
        return result;
    }

    private static boolean parseEnabled(String label, JsonNode node, boolean fallback) {
        if (node == null) {
            return fallback;
        }
        if (!node.isBoolean()) {
            throw new RulesetException(label + ": 'enabled' must be true or false, got " + node);
        }
        return node.asBoolean();
    }

    private static Severity parseSeverity(String label, String value) {
        try {
            return Severity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RulesetException(label + ": invalid severity '" + value + "' (use ERROR, WARNING or INFO)");
        }
    }

    private static Source parseSource(String label, JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new RulesetException(label + " must declare a 'source' mapping");
        }
        String statusText = requiredText(node, "status", label.toLowerCase(Locale.ROOT) + " source");
        SourceStatus status;
        try {
            status = SourceStatus.valueOf(statusText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RulesetException(label + ": invalid source status '" + statusText
                    + "' (use verified, secondary or unverified)");
        }
        List<String> urls = new ArrayList<>();
        JsonNode urlsNode = node.get("urls");
        if (urlsNode != null) {
            if (!urlsNode.isArray()) {
                throw new RulesetException(label + ": source.urls must be a list");
            }
            urlsNode.forEach(url -> urls.add(url.asText()));
        }
        if (status != SourceStatus.UNVERIFIED && urls.isEmpty()) {
            throw new RulesetException(label + ": a " + statusText + " source needs at least one URL");
        }
        JsonNode note = node.get("note");
        return new Source(status, urls, note == null || note.isNull() ? null : note.asText());
    }

    private static String requiredText(JsonNode node, String key, String where) {
        JsonNode value = node.get(key);
        if (value == null || !value.isValueNode() || value.asText().isBlank()) {
            throw new RulesetException(where + " is missing required key '" + key + "'");
        }
        return value.asText().trim();
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new RulesetException("checked_at must be an ISO date (yyyy-MM-dd), got '" + value + "'");
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
