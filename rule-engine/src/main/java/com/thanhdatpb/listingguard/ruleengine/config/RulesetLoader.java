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
 * <p>The content hash is computed over the text after line endings are normalized to LF and a
 * leading BOM is dropped, so the same file hashes the same on Windows and on Linux CI.
 */
public final class RulesetLoader {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("marketplace", "version", "checked_at", "rules");

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
        Severity severity = parseSeverity(id, requiredText(node, "severity", "rule '" + id + "'"));
        Source source = parseSource(id, node.get("source"));

        Map<String, Object> params = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            if (!field.getKey().equals("severity") && !field.getKey().equals("source")) {
                params.put(field.getKey(), YAML.convertValue(field.getValue(), Object.class));
            }
        }
        return new RuleSettings(id, severity, source, params);
    }

    private static Severity parseSeverity(String ruleId, String value) {
        try {
            return Severity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RulesetException("Rule '" + ruleId + "': invalid severity '" + value + "' (use ERROR, WARNING or INFO)");
        }
    }

    private static Source parseSource(String ruleId, JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new RulesetException("Rule '" + ruleId + "' must declare a 'source' mapping");
        }
        String statusText = requiredText(node, "status", "rule '" + ruleId + "' source");
        SourceStatus status;
        try {
            status = SourceStatus.valueOf(statusText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RulesetException("Rule '" + ruleId + "': invalid source status '" + statusText
                    + "' (use verified, secondary or unverified)");
        }
        List<String> urls = new ArrayList<>();
        JsonNode urlsNode = node.get("urls");
        if (urlsNode != null) {
            if (!urlsNode.isArray()) {
                throw new RulesetException("Rule '" + ruleId + "': source.urls must be a list");
            }
            urlsNode.forEach(url -> urls.add(url.asText()));
        }
        if (status != SourceStatus.UNVERIFIED && urls.isEmpty()) {
            throw new RulesetException("Rule '" + ruleId + "': a " + statusText + " source needs at least one URL");
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
