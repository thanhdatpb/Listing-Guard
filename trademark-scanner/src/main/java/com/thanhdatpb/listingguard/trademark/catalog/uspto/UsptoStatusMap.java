package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The map from the status codes of the USPTO Trademark Applications Daily XML (TDXF, DTD V2.0, Table 1 of the element
 * documentation) to the status of a catalog record, read from a versioned YAML file (ADR-009, PR A2b). Strict like the
 * rulesets: an unknown or missing key, a duplicate key, a bad code, a bad status or a {@code source.status} other than
 * {@code unverified} fails at load time.
 *
 * <p>A code is mapped to a {@link MarkStatus}, dropped (the code carries no status of the mark) or unmapped (not in
 * the file). The map is the maintainer's reading of the table; it is not a USPTO statement and not a legal conclusion.
 * {@code contentHash} is the SHA-256 of the text with LF line endings and no BOM, so the same file hashes the same on
 * every platform; the converter reports it.
 */
public final class UsptoStatusMap {

    /** The only input format this map and the converter know. */
    public static final String FORMAT = "trademark-applications-daily V2.0";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private static final Set<String> ROOT_KEYS = Set.of("version", "format", "checked_at", "source", "codes");
    private static final Set<String> SOURCE_KEYS = Set.of("status", "note");
    private static final Set<String> ENTRY_KEYS = Set.of("status", "basis", "meaning");

    /** What a code becomes. {@code DROP} is not a catalog status: the record is left out. */
    public enum Target {
        REGISTERED, PENDING, EXPIRED, CANCELLED, DROP
    }

    /** Where the decision comes from: the wording of Table 1, or the maintainer's choice. */
    public enum Basis {
        TABLE("table"), MAINTAINER("maintainer");

        private final String yaml;

        Basis(String yaml) {
            this.yaml = yaml;
        }

        public String yaml() {
            return yaml;
        }
    }

    public record Entry(Target target, Basis basis, String meaning) {
    }

    /** The result of looking a code up. */
    public sealed interface Outcome permits Mapped, Dropped, Unmapped {
    }

    public record Mapped(MarkStatus status) implements Outcome {
    }

    public record Dropped() implements Outcome {
    }

    public record Unmapped() implements Outcome {
    }

    private static final Dropped DROPPED = new Dropped();
    private static final Unmapped UNMAPPED = new Unmapped();

    private final String version;
    private final LocalDate checkedAt;
    private final String contentHash;
    private final Map<String, Entry> entries;

    private UsptoStatusMap(String version, LocalDate checkedAt, String contentHash, Map<String, Entry> entries) {
        this.version = version;
        this.checkedAt = checkedAt;
        this.contentHash = contentHash;
        this.entries = Map.copyOf(entries);
    }

    public static UsptoStatusMap loadClasspath(String path) {
        String resource = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = UsptoStatusMap.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UsptoStatusMapException("Status map not found on classpath: " + path);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UsptoStatusMapException("Cannot read the status map " + path, e);
        }
    }

    public static UsptoStatusMap parse(String yamlText) {
        String text = normalize(yamlText);
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new UsptoStatusMapException("Invalid status map YAML: " + e.getOriginalMessage(), e);
        }
        object(root, "the file", ROOT_KEYS);
        String version = text(root, "version");
        if (!version.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new UsptoStatusMapException("version must match [a-z0-9][a-z0-9._-]{0,63}, got '" + version + "'");
        }
        String format = text(root, "format");
        if (!FORMAT.equals(format)) {
            throw new UsptoStatusMapException("format must be '" + FORMAT + "', got '" + format + "'");
        }
        LocalDate checkedAt;
        try {
            checkedAt = LocalDate.parse(text(root, "checked_at"));
        } catch (DateTimeParseException e) {
            throw new UsptoStatusMapException("checked_at must be a date yyyy-MM-dd");
        }
        JsonNode source = root.get("source");
        object(source, "source", SOURCE_KEYS);
        if (!"unverified".equals(text(source, "status"))) {
            throw new UsptoStatusMapException("source.status must be 'unverified': nothing in this map is checked "
                    + "against a real file or by the USPTO");
        }
        text(source, "note");

        JsonNode codes = root.get("codes");
        if (codes == null || !codes.isObject() || codes.isEmpty()) {
            throw new UsptoStatusMapException("codes must be a non-empty map");
        }
        Map<String, Entry> entries = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = codes.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String code = field.getKey();
            if (!code.matches("\\d{3}")) {
                throw new UsptoStatusMapException("A status code is three digits, got '" + code + "'");
            }
            String where = "code " + code;
            JsonNode node = field.getValue();
            object(node, where, ENTRY_KEYS);
            entries.put(code, new Entry(target(text(node, "status"), where), basis(text(node, "basis"), where),
                    text(node, "meaning")));
        }
        return new UsptoStatusMap(version, checkedAt, sha256(text), entries);
    }

    /** What the code becomes: a catalog status, a drop, or unmapped when the file does not list it. */
    public Outcome outcome(String code) {
        Entry entry = code == null ? null : entries.get(code);
        if (entry == null) {
            return UNMAPPED;
        }
        return entry.target() == Target.DROP ? DROPPED : new Mapped(MarkStatus.valueOf(entry.target().name()));
    }

    public String version() {
        return version;
    }

    public LocalDate checkedAt() {
        return checkedAt;
    }

    public String contentHash() {
        return contentHash;
    }

    /** Every listed code, with its target, basis and the meaning copied from Table 1, in code order. */
    public Map<String, Entry> entries() {
        return new TreeMap<>(entries);
    }

    /** The node is a map holding exactly {@code keys}: none unknown, none missing. */
    private static void object(JsonNode node, String where, Set<String> keys) {
        if (node == null || !node.isObject()) {
            throw new UsptoStatusMapException(where + " must be a map");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!keys.contains(name)) {
                throw new UsptoStatusMapException(where + ": unknown key '" + name + "'");
            }
        }
        for (String key : keys) {
            if (!node.has(key)) {
                throw new UsptoStatusMapException(where + ": missing key '" + key + "'");
            }
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new UsptoStatusMapException("'" + key + "' must be a non-empty string");
        }
        return value.asText();
    }

    private static Target target(String value, String where) {
        for (Target target : Target.values()) {
            if (target.name().equals(value)) {
                return target;
            }
        }
        throw new UsptoStatusMapException(where + ": status must be one of " + java.util.List.of(Target.values())
                + ", got '" + value + "'");
    }

    private static Basis basis(String value, String where) {
        for (Basis basis : Basis.values()) {
            if (basis.yaml().equals(value)) {
                return basis;
            }
        }
        throw new UsptoStatusMapException(where + ": basis must be 'table' or 'maintainer', got '" + value + "'");
    }

    private static String normalize(String text) {
        String t = text.startsWith("﻿") ? text.substring(1) : text;
        return t.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
