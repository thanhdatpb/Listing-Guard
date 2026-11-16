package com.thanhdatpb.listingguard.trademark.watchlist;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.config.Templates;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;

/**
 * The settings of watchlist matching (ADR-031): the minimum level of a watchlist finding and its Vietnamese templates. A
 * versioned, immutable YAML like a ruleset (ADR-002): a change is a new file with a new version. Strict: an unknown or missing
 * key, a bad level, an unknown placeholder or a {@code source.status} other than {@code unverified} fails at load time.
 *
 * @param version      the settings version, for example {@code trademark-watchlist-2026-11}
 * @param contentHash  SHA-256 of the file after line endings are normalized to LF and a BOM is dropped
 * @param checkedAt    when the settings were last reviewed
 * @param minimumRisk  the lowest level a watchlist finding is reported at (LOW, MEDIUM or HIGH)
 * @param message      placeholders {@code token}, {@code field}, {@code mark}
 * @param aliasNote    added to the message when an alias matched; placeholders {@code alias}, {@code mark}
 * @param reasonNote   added to the message when the entry has a note; placeholder {@code note}
 * @param suggestion   placeholders {@code mark}, {@code field}
 */
public record WatchlistSettings(String version, String contentHash, LocalDate checkedAt, RiskLevel minimumRisk,
                                String message, String aliasNote, String reasonNote, String suggestion) {

    /** The settings the API ships with. */
    public static final String SHIPPED = "watchlist/trademark-watchlist-2026-11.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> KEYS = Set.of("pack_id", "version", "checked_at", "source", "minimum_risk", "message",
            "alias_note", "reason_note", "suggestion");

    public static WatchlistSettings loadClasspath(String path) {
        String resource = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = WatchlistSettings.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new TrademarkRulesetException("Watchlist settings not found on classpath: " + path);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TrademarkRulesetException("Cannot read watchlist settings " + path, e);
        }
    }

    public static WatchlistSettings parse(String yamlText) {
        String text = yamlText.startsWith("﻿") ? yamlText.substring(1) : yamlText;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        JsonNode tree;
        try {
            tree = YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new TrademarkRulesetException("Invalid watchlist settings YAML: " + e.getOriginalMessage(), e);
        }
        if (tree == null || !tree.isObject()) {
            throw new TrademarkRulesetException("Watchlist settings must be a YAML mapping");
        }
        for (Iterator<String> names = tree.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!KEYS.contains(name)) {
                throw new TrademarkRulesetException("Watchlist settings: unknown key '" + name + "'");
            }
        }
        String packId = text(tree, "pack_id");
        if (!TrademarkRuleset.PACK_ID.equals(packId)) {
            throw new TrademarkRulesetException("Watchlist settings: pack_id must be " + TrademarkRuleset.PACK_ID);
        }
        String version = text(tree, "version");
        if (!version.matches("[a-z0-9][a-z0-9.-]{0,63}")) {
            throw new TrademarkRulesetException("Watchlist settings: version must match [a-z0-9][a-z0-9.-]{0,63}");
        }
        LocalDate checkedAt;
        try {
            checkedAt = LocalDate.parse(text(tree, "checked_at"));
        } catch (DateTimeParseException e) {
            throw new TrademarkRulesetException("Watchlist settings: checked_at must be a date (YYYY-MM-DD)", e);
        }
        JsonNode source = tree.get("source");
        if (source == null || !source.isObject() || source.get("status") == null
                || !"unverified".equals(source.get("status").asText())) {
            throw new TrademarkRulesetException("Watchlist settings: source.status must be 'unverified' (ADR-031: the watchlist "
                    + "is the client's own list, not a marketplace or legal rule)");
        }
        for (Iterator<String> names = source.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!name.equals("status") && !name.equals("note")) {
                throw new TrademarkRulesetException("Watchlist settings: unknown key 'source." + name + "'");
            }
        }
        RiskLevel minimum;
        try {
            minimum = RiskLevel.valueOf(text(tree, "minimum_risk"));
        } catch (IllegalArgumentException e) {
            throw new TrademarkRulesetException("Watchlist settings: minimum_risk must be LOW, MEDIUM or HIGH", e);
        }
        if (!minimum.isDetermined()) {
            throw new TrademarkRulesetException("Watchlist settings: minimum_risk must be LOW, MEDIUM or HIGH");
        }
        String message = template(tree, "message", Set.of("token", "field", "mark"));
        String aliasNote = template(tree, "alias_note", Set.of("alias", "mark"));
        String reasonNote = template(tree, "reason_note", Set.of("note"));
        String suggestion = template(tree, "suggestion", Set.of("mark", "field"));
        return new WatchlistSettings(version, sha256(text), checkedAt, minimum, message, aliasNote, reasonNote, suggestion);
    }

    /** The fingerprint component naming these settings: version and content hash. */
    public String fingerprintComponent() {
        return version + ":" + contentHash;
    }

    private static String template(JsonNode tree, String key, Set<String> allowed) {
        String value = text(tree, key);
        Templates.validate("Watchlist settings '" + key + "'", value, allowed);
        return value;
    }

    private static String text(JsonNode tree, String key) {
        JsonNode node = tree.get(key);
        if (node == null || node.isNull() || !node.isValueNode() || node.asText().isBlank()) {
            throw new TrademarkRulesetException("Watchlist settings: '" + key + "' is required and must be text");
        }
        return node.asText();
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
