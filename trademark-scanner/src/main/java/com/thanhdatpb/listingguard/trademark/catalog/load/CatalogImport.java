package com.thanhdatpb.listingguard.trademark.catalog.load;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns one local data file into a {@link PreparedCatalog} (ADR-009, PR A2). Reads a file and nothing else: no
 * network, no database. The whole file is validated before anything is filtered, so one bad line rejects the batch,
 * and the error names the line number but never the text of a mark.
 *
 * <p>Data file ({@value #FORMAT_VERSION}): UTF-8 JSON Lines, one record per non-blank line with the keys
 * {@code mark} (the display form), {@code status}, {@code nice_classes} (non-empty, each 1 to 45),
 * {@code source_id} and optionally {@code common_word} (default false).
 *
 * <p>Then: each mark is normalized with the active {@link TextNormalizer}; a record is kept when at least one of its
 * classes is in {@code include_classes} and its status is in {@code include_status}; kept records with the same
 * normalized mark are merged into one entry: the classes are united, {@code common_word} is true if any record says
 * so, the status is the one declared first in {@link MarkStatus}, and the display form (with its source id) is the
 * smallest by {@code String.compareTo}, ties broken by the smaller source id. An import that keeps nothing is
 * rejected.
 */
public final class CatalogImport {

    public static final String FORMAT_VERSION = "jsonl-1";
    public static final String IDENTITY_FORMAT = "listing-guard/trademark-catalog-identity/v1";

    private static final Set<String> RECORD_KEYS = Set.of("mark", "status", "nice_classes", "source_id", "common_word");
    private static final int MAX_MARK_CHARS = 300;
    private static final int MAX_SOURCE_ID_CHARS = 100;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private CatalogImport() {
    }

    private record Row(String display, String normalized, MarkStatus status, Set<Integer> classes, String sourceId,
                       boolean commonWord) {
    }

    /**
     * @param rawDir the directory holding the data file named by the manifest entry
     * @throws CatalogFormatException the file or the manifest entry is invalid, or the file hash differs
     */
    public static PreparedCatalog prepare(CatalogManifest.Entry entry, Path rawDir, TextNormalizer normalizer,
                                          NormalizationIdentity identity) {
        String where = "Catalog '" + entry.version() + "'";
        if (!FORMAT_VERSION.equals(entry.formatVersion())) {
            throw new CatalogFormatException(where + ": format_version '" + entry.formatVersion()
                    + "' is not supported (use " + FORMAT_VERSION + ")");
        }
        Path file = rawDir.resolve(entry.file()).normalize();
        if (!file.startsWith(rawDir.normalize()) || !Files.isRegularFile(file)) {
            throw new CatalogFormatException(where + ": the data file " + entry.file() + " is not in " + rawDir);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new CatalogFormatException(where + ": cannot read " + entry.file(), e);
        }
        String actualHash = sha256(bytes);
        if (!actualHash.equals(entry.sha256())) {
            throw new CatalogFormatException(where + ": the SHA-256 of " + entry.file() + " is " + actualHash
                    + " but the manifest says " + entry.sha256() + "; the file changed, or the manifest is stale");
        }

        List<Row> rows = parse(where, decode(where, bytes), normalizer);
        List<Row> kept = new ArrayList<>();
        for (Row row : rows) {
            if (row.status() != null && entry.includeStatus().contains(row.status())
                    && row.classes().stream().anyMatch(entry.includeClasses()::contains)) {
                kept.add(row);
            }
        }
        List<PreparedCatalog.Entry> entries = merge(kept);
        if (entries.isEmpty()) {
            throw new CatalogFormatException(where + ": no record is left after the filters; check include_classes "
                    + "and include_status");
        }
        String filtersJson = entry.filtersJson();
        String version = entry.version() + "-" + digest(entry, filtersJson, identity).substring(0, 12);
        return new PreparedCatalog(version, entry.version(), entry.sourceName(), entry.url(), entry.license(),
                entry.sourceDate(), actualHash, entry.formatVersion(), filtersJson, identity, rows.size(),
                rows.size() - kept.size(), kept.size() - entries.size(), entries);
    }

    private static String decode(String where, byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new CatalogFormatException(where + ": the data file is not valid UTF-8", e);
        }
    }

    private static List<Row> parse(String where, String text, TextNormalizer normalizer) {
        List<Row> rows = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            if (line.isBlank()) {
                continue;
            }
            rows.add(row(where + ", line " + (i + 1), line, normalizer));
        }
        return rows;
    }

    private static Row row(String where, String line, TextNormalizer normalizer) {
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (JsonProcessingException e) {
            throw new CatalogFormatException(where + ": not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new CatalogFormatException(where + ": a record must be a JSON object");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!RECORD_KEYS.contains(name)) {
                throw new CatalogFormatException(where + ": unknown key '" + name + "'");
            }
        }
        String display = requiredText(where, node, "mark");
        String sourceId = requiredText(where, node, "source_id");
        if (display.length() > MAX_MARK_CHARS || sourceId.length() > MAX_SOURCE_ID_CHARS) {
            throw new CatalogFormatException(where + ": mark or source_id is too long");
        }
        String statusText = requiredText(where, node, "status");
        MarkStatus status;
        try {
            status = MarkStatus.valueOf(statusText);
        } catch (IllegalArgumentException e) {
            throw new CatalogFormatException(where + ": unknown status (use " + List.of(MarkStatus.values()) + ")");
        }
        JsonNode classes = node.get("nice_classes");
        if (classes == null || !classes.isArray() || classes.isEmpty()) {
            throw new CatalogFormatException(where + ": nice_classes must be a non-empty list");
        }
        Set<Integer> niceClasses = new TreeSet<>();
        for (JsonNode item : classes) {
            if (!item.isIntegralNumber() || item.asInt() < 1 || item.asInt() > 45) {
                throw new CatalogFormatException(where + ": nice_classes must hold integers from 1 to 45");
            }
            niceClasses.add(item.asInt());
        }
        boolean commonWord = false;
        if (node.has("common_word")) {
            if (!node.get("common_word").isBoolean()) {
                throw new CatalogFormatException(where + ": common_word must be true or false");
            }
            commonWord = node.get("common_word").asBoolean();
        }
        String normalized = normalizer.normalizeMark(display);
        if (normalized.isEmpty()) {
            throw new CatalogFormatException(where + ": the mark has no letters or digits after normalization");
        }
        if (normalized.length() > MAX_MARK_CHARS) {
            throw new CatalogFormatException(where + ": the normalized mark is too long");
        }
        return new Row(display, normalized, status, niceClasses, sourceId, commonWord);
    }

    private static String requiredText(String where, JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new CatalogFormatException(where + ": '" + key + "' is missing or not a non-empty string");
        }
        return value.asText();
    }

    private static List<PreparedCatalog.Entry> merge(List<Row> kept) {
        Map<String, List<Row>> byNormalized = new TreeMap<>();
        for (Row row : kept) {
            byNormalized.computeIfAbsent(row.normalized(), k -> new ArrayList<>()).add(row);
        }
        List<PreparedCatalog.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, List<Row>> group : byNormalized.entrySet()) {
            Row display = group.getValue().stream()
                    .min(Comparator.comparing(Row::display).thenComparing(Row::sourceId)).orElseThrow();
            Set<Integer> classes = new TreeSet<>();
            MarkStatus status = null;
            boolean common = false;
            for (Row row : group.getValue()) {
                classes.addAll(row.classes());
                common |= row.commonWord();
                if (status == null || row.status().compareTo(status) < 0) {
                    status = row.status();
                }
            }
            entries.add(new PreparedCatalog.Entry(
                    new CatalogMark(group.getKey(), display.display(), List.copyOf(classes), status, common),
                    display.sourceId()));
        }
        return entries;
    }

    /** A digest of everything that identifies the import, so any difference is a different catalog version. */
    static String digest(CatalogManifest.Entry entry, String filtersJson, NormalizationIdentity identity) {
        String text = IDENTITY_FORMAT + "\n"
                + "label=" + entry.version() + "\n"
                + "source_name=" + entry.sourceName() + "\n"
                + "url=" + entry.url() + "\n"
                + "license=" + entry.license() + "\n"
                + "source_date=" + entry.sourceDate() + "\n"
                + "file_sha256=" + entry.sha256() + "\n"
                + "format_version=" + entry.formatVersion() + "\n"
                + "filters=" + filtersJson + "\n"
                + "pack_id=" + identity.packId() + "\n"
                + "ruleset_version=" + identity.rulesetVersion() + "\n"
                + "ruleset_content_hash=" + identity.rulesetContentHash() + "\n"
                + "normalization_version=" + identity.normalizationVersion() + "\n";
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 of a file's bytes as lower-case hex: what the manifest holds. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
