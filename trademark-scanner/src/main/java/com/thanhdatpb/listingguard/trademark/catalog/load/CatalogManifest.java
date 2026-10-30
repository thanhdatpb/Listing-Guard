package com.thanhdatpb.listingguard.trademark.catalog.load;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code catalogs/manifest.yaml}: which local data files may be imported and where they come from. Only the manifest
 * is committed; the data files live in the git-ignored {@code catalogs/raw/} (the same convention as
 * {@code policies/}). Strict: an unknown or missing key, a missing license or source date, or a bad filter is an
 * error, so a file with unclear terms cannot be imported.
 *
 * <p>An entry:
 * <pre>
 * - version: synthetic-2026-11      # label; the stored version adds a digest of the import identity
 *   file: synthetic.jsonl           # a plain file name inside raw/
 *   format_version: jsonl-1
 *   source_name: ...
 *   url: ...                        # public location or other reference of the source
 *   license: ...
 *   source_date: 2026-10-30
 *   sha256: ...                     # of the file; the import is refused when it differs
 *   filters:
 *     include_classes: [25, 35]     # Nice classes, 1 to 45
 *     include_status: [REGISTERED, PENDING]
 * </pre>
 */
public record CatalogManifest(List<Entry> catalogs) {

    public static final String FILE_NAME = "manifest.yaml";

    /** @param includeClasses sorted; @param includeStatus sorted by declaration order of {@link MarkStatus} */
    public record Entry(String version, String file, String formatVersion, String sourceName, String url,
                        String license, LocalDate sourceDate, String sha256, Set<Integer> includeClasses,
                        Set<MarkStatus> includeStatus) {
        public Entry {
            includeClasses = Set.copyOf(new TreeSet<>(includeClasses));
            includeStatus = Set.copyOf(new TreeSet<>(includeStatus));
        }

        /** The filters as canonical JSON: sorted keys, sorted values. */
        public String filtersJson() {
            String classes = new TreeSet<>(includeClasses).stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            String statuses = new TreeSet<>(includeStatus).stream().map(status -> "\"" + status + "\"")
                    .collect(java.util.stream.Collectors.joining(","));
            return "{\"include_classes\":[" + classes + "],\"include_status\":[" + statuses + "]}";
        }
    }

    private static final Set<String> ENTRY_KEYS = Set.of("version", "file", "format_version", "source_name", "url",
            "license", "source_date", "sha256", "filters");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public CatalogManifest {
        catalogs = List.copyOf(catalogs);
    }

    public Entry entry(String version) {
        return catalogs.stream().filter(e -> e.version().equals(version)).findFirst()
                .orElseThrow(() -> new CatalogFormatException("The manifest has no catalog '" + version
                        + "' (known: " + catalogs.stream().map(Entry::version).toList() + ")"));
    }

    /** Reads {@code <dir>/manifest.yaml}. */
    public static CatalogManifest load(Path dir) {
        Path file = dir.resolve(FILE_NAME);
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CatalogFormatException("Cannot read the catalog manifest " + file, e);
        }
    }

    public static CatalogManifest parse(String yaml) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (JsonProcessingException e) {
            throw new CatalogFormatException("Invalid catalog manifest YAML: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject() || !root.has("catalogs") || !root.get("catalogs").isArray()) {
            throw new CatalogFormatException("The catalog manifest must be a mapping with a 'catalogs' list");
        }
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!name.equals("catalogs")) {
                throw new CatalogFormatException("Unknown top-level key '" + name + "' in the catalog manifest");
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode node : root.get("catalogs")) {
            Entry entry = entry(node);
            if (entries.stream().anyMatch(e -> e.version().equals(entry.version()))) {
                throw new CatalogFormatException("Catalog '" + entry.version() + "' is listed twice");
            }
            entries.add(entry);
        }
        return new CatalogManifest(entries);
    }

    private static Entry entry(JsonNode node) {
        if (!node.isObject()) {
            throw new CatalogFormatException("Each catalog entry must be a mapping");
        }
        String label = text(node, "version", "?");
        String where = "catalog '" + label + "'";
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!ENTRY_KEYS.contains(name)) {
                throw new CatalogFormatException("Unknown key '" + name + "' in " + where);
            }
        }
        if (!label.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new CatalogFormatException("version of " + where + " must match [a-z0-9][a-z0-9._-]{0,63}");
        }
        String file = text(node, "file", where);
        if (!file.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
            throw new CatalogFormatException("file of " + where + " must be a plain file name inside raw/, got '"
                    + file + "'");
        }
        String format = text(node, "format_version", where);
        String sha256 = text(node, "sha256", where).toLowerCase(java.util.Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new CatalogFormatException("sha256 of " + where + " must be 64 hex characters");
        }
        String dateText = text(node, "source_date", where);
        LocalDate sourceDate;
        try {
            sourceDate = LocalDate.parse(dateText);
        } catch (DateTimeParseException e) {
            throw new CatalogFormatException("source_date of " + where + " must be an ISO date, got '" + dateText + "'");
        }
        JsonNode filters = node.get("filters");
        if (filters == null || !filters.isObject()) {
            throw new CatalogFormatException(where + " needs a 'filters' mapping");
        }
        for (Iterator<String> names = filters.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!name.equals("include_classes") && !name.equals("include_status")) {
                throw new CatalogFormatException("Unknown filter '" + name + "' in " + where);
            }
        }
        return new Entry(label, file, format, text(node, "source_name", where), text(node, "url", where),
                text(node, "license", where), sourceDate, sha256, classes(filters, where), statuses(filters, where));
    }

    private static String text(JsonNode node, String key, String where) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull() || !value.isValueNode()
                || value.asText().isBlank()) {
            throw new CatalogFormatException(where + " is missing required key '" + key + "'");
        }
        return value.asText().trim();
    }

    private static Set<Integer> classes(JsonNode filters, String where) {
        JsonNode list = filters.get("include_classes");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new CatalogFormatException("filters.include_classes of " + where + " must be a non-empty list");
        }
        Set<Integer> classes = new TreeSet<>();
        for (JsonNode item : list) {
            if (!item.isIntegralNumber() || item.asInt() < 1 || item.asInt() > 45) {
                throw new CatalogFormatException("filters.include_classes of " + where
                        + " must hold Nice classes from 1 to 45, got " + item);
            }
            classes.add(item.asInt());
        }
        return classes;
    }

    private static Set<MarkStatus> statuses(JsonNode filters, String where) {
        JsonNode list = filters.get("include_status");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new CatalogFormatException("filters.include_status of " + where + " must be a non-empty list");
        }
        Set<MarkStatus> statuses = new TreeSet<>();
        for (JsonNode item : list) {
            try {
                statuses.add(MarkStatus.valueOf(item.asText()));
            } catch (IllegalArgumentException e) {
                throw new CatalogFormatException("filters.include_status of " + where + " has an unknown status "
                        + item + " (use " + List.of(MarkStatus.values()) + ")");
            }
        }
        return statuses;
    }
}
