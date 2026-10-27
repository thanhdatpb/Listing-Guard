package com.thanhdatpb.listingguard.rag.policy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict reader of {@code policies/manifest.yaml}: unknown keys, missing keys and duplicates all fail. */
public final class PolicyManifestLoader {

    private static final Set<String> ENTRY_KEYS = Set.of("file", "marketplace", "title", "url", "fetched_at", "version");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private PolicyManifestLoader() {
    }

    public static PolicyManifest load(Path manifestFile) {
        try {
            return parse(Files.readString(manifestFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PolicyParseException("Cannot read manifest " + manifestFile, e);
        }
    }

    public static PolicyManifest parse(String yamlText) {
        JsonNode root;
        try {
            root = YAML.readTree(yamlText);
        } catch (JsonProcessingException e) {
            throw new PolicyParseException("Invalid manifest YAML: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject() || !root.hasNonNull("documents") || !root.get("documents").isArray()) {
            throw new PolicyParseException("Manifest must have a 'documents' list");
        }
        List<PolicyManifest.Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (JsonNode node : root.get("documents")) {
            PolicyManifest.Entry entry = parseEntry(node, "documents[" + index++ + "]");
            if (!seen.add(entry.marketplace() + "/" + entry.file())) {
                throw new PolicyParseException("Duplicate manifest entry for " + entry.marketplace() + "/" + entry.file());
            }
            entries.add(entry);
        }
        return new PolicyManifest(entries);
    }

    private static PolicyManifest.Entry parseEntry(JsonNode node, String where) {
        if (!node.isObject()) {
            throw new PolicyParseException(where + " must be a mapping");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!ENTRY_KEYS.contains(name)) {
                throw new PolicyParseException(where + " has unknown key '" + name + "'");
            }
        }
        String url = required(node, "url", where);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new PolicyParseException(where + ": url must start with http:// or https://, got '" + url + "'");
        }
        return new PolicyManifest.Entry(
                required(node, "file", where),
                required(node, "marketplace", where).toUpperCase(Locale.ROOT),
                required(node, "title", where),
                url,
                date(required(node, "fetched_at", where), where),
                required(node, "version", where));
    }

    private static String required(JsonNode node, String key, String where) {
        JsonNode value = node.get(key);
        if (value == null || !value.isValueNode() || value.asText().isBlank()) {
            throw new PolicyParseException(where + " is missing required key '" + key + "'");
        }
        return value.asText().trim();
    }

    private static LocalDate date(String value, String where) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new PolicyParseException(where + ": fetched_at must be an ISO date (yyyy-MM-dd), got '" + value + "'");
        }
    }
}
