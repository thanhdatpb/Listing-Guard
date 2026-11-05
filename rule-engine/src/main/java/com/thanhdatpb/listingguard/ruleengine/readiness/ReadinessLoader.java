package com.thanhdatpb.listingguard.ruleengine.readiness;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses a readiness YAML into a {@link ReadinessConfig}. Strict on purpose, like the ruleset loader: an unknown or
 * duplicate key, a missing key, a number that is not an integer, a severity that is missing from {@code penalties}, or
 * a {@code source} that claims more than {@code unverified} fails at load time. The content hash is computed over the
 * text after line endings are normalized to LF and a leading BOM is dropped.
 */
public final class ReadinessLoader {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "version", "checked_at", "pack_id", "base_score", "penalties", "not_ready_max_score", "source");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ReadinessLoader() {
    }

    public static ReadinessConfig loadClasspath(String path) {
        String resource = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = ReadinessLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new RulesetException("Readiness config not found on classpath: " + path);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RulesetException("Cannot read readiness config " + path, e);
        }
    }

    public static ReadinessConfig parse(String yamlText) {
        String text = normalize(yamlText);
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new RulesetException("Invalid readiness YAML: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new RulesetException("A readiness config must be a YAML mapping");
        }
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!TOP_LEVEL_KEYS.contains(name)) {
                throw new RulesetException("Unknown top-level key '" + name + "'");
            }
        }
        parseSource(root.get("source"));
        String packId = requiredText(root, "pack_id").toLowerCase(Locale.ROOT);
        if (!packId.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new RulesetException("pack_id must match [a-z][a-z0-9-]{0,63}");
        }
        return new ReadinessConfig(
                requiredText(root, "version"),
                parseDate(requiredText(root, "checked_at")),
                sha256(text),
                packId,
                requiredInt(root, "base_score"),
                parsePenalties(root.get("penalties")),
                requiredInt(root, "not_ready_max_score"));
    }

    /** LF line endings, no BOM. This is the text that gets hashed. */
    static String normalize(String text) {
        String withoutBom = text.startsWith("﻿") ? text.substring(1) : text;
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Map<Severity, Integer> parsePenalties(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new RulesetException("'penalties' must be a mapping of ERROR, WARNING and INFO to numbers");
        }
        Map<Severity, Integer> penalties = new EnumMap<>(Severity.class);
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            Severity severity;
            try {
                severity = Severity.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new RulesetException("penalties has unknown severity '" + name + "' (use ERROR, WARNING, INFO)");
            }
            penalties.put(severity, requiredInt(node, name));
        }
        for (Severity severity : Severity.values()) {
            if (!penalties.containsKey(severity)) {
                throw new RulesetException("penalties is missing '" + severity + "'");
            }
        }
        return penalties;
    }

    /** The weights are a maintainer's choice, so the only honest status is unverified, with a note saying so. */
    private static void parseSource(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new RulesetException("A readiness config must declare a 'source' mapping");
        }
        if (!"unverified".equals(requiredText(node, "status").toLowerCase(Locale.ROOT))) {
            throw new RulesetException("source.status must be 'unverified': the weights are not backed by a document");
        }
        requiredText(node, "note");
    }

    private static int requiredInt(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new RulesetException("'" + key + "' must be a whole number, got " + value);
        }
        return value.asInt();
    }

    private static String requiredText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isValueNode() || value.asText().isBlank()) {
            throw new RulesetException("Readiness config is missing required key '" + key + "'");
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
