package com.thanhdatpb.listingguard.trademark.catalog.load;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads the local data files of one manifest entry (ADR-009, PR A2, A2b). Reads files and nothing else: no network, no
 * database. A file is read as a stream, one line at a time, so the memory it needs does not depend on its size.
 *
 * <p>Data file ({@value #FORMAT_VERSION}): UTF-8 JSON Lines, one record per non-blank line with the keys
 * {@code mark} (the display form), {@code status}, {@code nice_classes} (non-empty, each 1 to 45),
 * {@code source_id} and optionally {@code common_word} (default false). Lines end with LF or CR LF.
 *
 * <p>{@link #verifyFiles} checks every file against the hash of the manifest, so a changed file refuses the whole
 * catalog before anything else happens; {@link #header} gives the identity of the import; {@link #scan} validates
 * every line of every file (one bad line rejects the batch, the error names the source and the line number and never
 * the text of a mark), normalizes each mark with the active {@link TextNormalizer}, and passes the records that the
 * filters keep to a {@link RecordSink}. A record is kept when at least one of its classes is in
 * {@code include_classes} and its status is in {@code include_status}; the filters apply to the whole catalog.
 *
 * <p>Records with the same normalized mark become one catalog entry. The rule: the classes are united,
 * {@code common_word} is true if any record says so, the status is the one declared first in {@link MarkStatus}, the
 * source ids of all merged records are kept (ascending, without duplicates) and the display form is the smallest one,
 * ties broken by the smaller source id. "Smallest" and "ascending" are in code point order, which is the binary
 * order of UTF-8 that PostgreSQL gives with {@code COLLATE "C"}. {@link #prepare} applies the rule in memory: it is the
 * reference for tests and small files, while the importer of the API module applies the same rule in SQL over a
 * staging table, so that a large file is never held in memory.
 *
 * <p>With the {@code sources:} form every source id is stored as {@code <source_key>:<source_id>}, so the ids of
 * different files cannot collide and show where a mark came from; the one-file form stores the ids as the file gives
 * them. The outcome does not depend on the order of the sources.
 */
public final class CatalogImport {

    public static final String FORMAT_VERSION = "jsonl-1";
    /** The digest of a one-file entry. Do not change it: the versions already imported would stop being found. */
    public static final String IDENTITY_FORMAT = "listing-guard/trademark-catalog-identity/v1";
    /** The digest of an entry with {@code sources:}: it lists every source, ordered by key. */
    public static final String IDENTITY_FORMAT_SOURCES = "listing-guard/trademark-catalog-identity/v2";

    private static final String FILES_FORMAT = "listing-guard/trademark-catalog-files/v1";
    private static final String SOURCE_SEPARATOR = " | ";
    /** Widths of source_name, source_reference and source_license in migration V15. */
    private static final int MAX_NAME_CHARS = 200;
    private static final int MAX_REFERENCE_CHARS = 500;
    private static final int MAX_LICENSE_CHARS = 200;
    private static final Set<String> RECORD_KEYS = Set.of("mark", "status", "nice_classes", "source_id", "common_word");
    private static final int MAX_MARK_CHARS = 300;
    private static final int MAX_SOURCE_ID_CHARS = 100;
    /** A valid line is far shorter; the limit keeps a file without line breaks from filling the memory. */
    private static final int MAX_LINE_CHARS = 100_000;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private CatalogImport() {
    }

    /**
     * A record that passed the validation and the filters.
     *
     * @param display  the mark as the source shows it
     * @param sourceId the id as stored: prefixed with {@code <source_key>:} in the {@code sources:} form
     */
    public record CatalogRecord(String display, String normalized, MarkStatus status, Set<Integer> classes,
                                String sourceId, boolean commonWord) {
        public CatalogRecord {
            classes = Set.copyOf(classes);
        }
    }

    /** Receives the records that the filters keep, in file order (the sources ordered by key). */
    @FunctionalInterface
    public interface RecordSink {
        void accept(CatalogRecord record);
    }

    /** @param recordsRead non-blank lines read from all files; @param recordsKept those that the filters keep */
    public record ScanResult(int recordsRead, int recordsKept) {
    }

    /**
     * The error for an import whose filters keep nothing.
     */
    public static CatalogFormatException nothingLeft(String label) {
        return new CatalogFormatException("Catalog '" + label + "': no record is left after the filters; check "
                + "include_classes and include_status");
    }

    /**
     * Checks that every data file of the entry is inside {@code rawDir} and has the hash of the manifest. The files are
     * read as streams; nothing is parsed.
     *
     * @throws CatalogFormatException a file is missing or outside {@code rawDir}, or its hash differs
     */
    public static void verifyFiles(CatalogManifest.Entry entry, Path rawDir) {
        for (CatalogManifest.Source source : entry.sources()) {
            String where = whereSource(entry, source);
            Path file = locate(where, source, rawDir);
            String actual = hashOf(where, source, file);
            checkHash(where, source, actual);
        }
    }

    /**
     * The identity of the import: it depends on the manifest entry and the normalization identity, not on the records.
     *
     * @throws CatalogFormatException the format is not supported, or the source texts do not fit the catalog table
     */
    public static CatalogHeader header(CatalogManifest.Entry entry, NormalizationIdentity identity) {
        String where = "Catalog '" + entry.version() + "'";
        if (!FORMAT_VERSION.equals(entry.formatVersion())) {
            throw new CatalogFormatException(where + ": format_version '" + entry.formatVersion()
                    + "' is not supported (use " + FORMAT_VERSION + ")");
        }
        String filtersJson = entry.filtersJson();
        String version = entry.version() + "-" + digest(entry, filtersJson, identity).substring(0, 12);
        String sourceName = joined(where, "source_name", MAX_NAME_CHARS, entry, CatalogManifest.Source::sourceName);
        String reference = joined(where, "url", MAX_REFERENCE_CHARS, entry, CatalogManifest.Source::url);
        String license = joined(where, "license", MAX_LICENSE_CHARS, entry, CatalogManifest.Source::license);
        // the catalog is no newer than its oldest source
        LocalDate sourceDate = entry.sources().stream().map(CatalogManifest.Source::sourceDate)
                .min(Comparator.naturalOrder()).orElseThrow();
        return new CatalogHeader(version, entry.version(), sourceName, reference, license, sourceDate,
                filesHash(entry), entry.formatVersion(), filtersJson, identity, entry.sources());
    }

    /**
     * Reads every file of the entry line by line and passes the kept records to {@code sink}. The hash of the bytes
     * that were parsed is checked against the manifest at the end of each file, so a file that changes after
     * {@link #verifyFiles} is refused too. The memory used does not depend on the size of the files.
     *
     * @throws CatalogFormatException a line, a file or the manifest entry is invalid, or a file hash differs
     */
    public static ScanResult scan(CatalogManifest.Entry entry, Path rawDir, TextNormalizer normalizer,
                                  RecordSink sink) {
        if (!FORMAT_VERSION.equals(entry.formatVersion())) {
            throw new CatalogFormatException("Catalog '" + entry.version() + "': format_version '"
                    + entry.formatVersion() + "' is not supported (use " + FORMAT_VERSION + ")");
        }
        int read = 0;
        int kept = 0;
        for (CatalogManifest.Source source : entry.sources()) {
            String where = whereSource(entry, source);
            Path file = locate(where, source, rawDir);
            MessageDigest digest = newDigest();
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in,
                         StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                 .onUnmappableCharacter(CodingErrorAction.REPORT)), BUFFER_BYTES)) {
                StringBuilder buffer = new StringBuilder();
                for (int lineNumber = 1; ; lineNumber++) {
                    String line = readLine(reader, buffer, where + ", line " + lineNumber);
                    if (line == null) {
                        break;
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    CatalogRecord record = record(where + ", line " + lineNumber, line, normalizer, source.key());
                    read++;
                    if (entry.includeStatus().contains(record.status())
                            && record.classes().stream().anyMatch(entry.includeClasses()::contains)) {
                        kept++;
                        sink.accept(record);
                    }
                }
            } catch (CharacterCodingException e) {
                throw new CatalogFormatException(where + ": the data file is not valid UTF-8", e);
            } catch (IOException e) {
                throw new CatalogFormatException(where + ": cannot read " + source.file(), e);
            }
            checkHash(where, source, HexFormat.of().formatHex(digest.digest()));
        }
        return new ScanResult(read, kept);
    }

    /**
     * The whole import in memory, the reference for the SQL of the API importer: verifies the files, reads them, applies
     * the merge rule. Fit for tests and small files; a large file belongs to the streaming importer.
     */
    public static PreparedCatalog prepare(CatalogManifest.Entry entry, Path rawDir, TextNormalizer normalizer,
                                          NormalizationIdentity identity) {
        verifyFiles(entry, rawDir);
        CatalogHeader header = header(entry, identity);
        List<CatalogRecord> kept = new ArrayList<>();
        ScanResult counts = scan(entry, rawDir, normalizer, kept::add);
        List<PreparedCatalog.Entry> entries = merge(kept);
        if (entries.isEmpty()) {
            throw nothingLeft(entry.version());
        }
        return new PreparedCatalog(header.version(), header.label(), header.sourceName(), header.sourceReference(),
                header.sourceLicense(), header.sourceDate(), header.fileSha256(), header.formatVersion(),
                header.filtersJson(), identity, counts.recordsRead(), counts.recordsRead() - counts.recordsKept(),
                kept.size() - entries.size(), entries);
    }

    private static String whereSource(CatalogManifest.Entry entry, CatalogManifest.Source source) {
        String where = "Catalog '" + entry.version() + "'";
        return entry.keyed() ? where + ", source '" + source.key() + "'" : where;
    }

    private static Path locate(String where, CatalogManifest.Source source, Path rawDir) {
        Path file = rawDir.resolve(source.file()).normalize();
        if (!file.startsWith(rawDir.normalize()) || !Files.isRegularFile(file)) {
            throw new CatalogFormatException(where + ": the data file " + source.file() + " is not in " + rawDir);
        }
        return file;
    }

    private static String hashOf(String where, CatalogManifest.Source source, Path file) {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new CatalogFormatException(where + ": cannot read " + source.file(), e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void checkHash(String where, CatalogManifest.Source source, String actual) {
        if (!actual.equals(source.sha256())) {
            throw new CatalogFormatException(where + ": the SHA-256 of " + source.file() + " is " + actual
                    + " but the manifest says " + source.sha256() + "; the file changed, or the manifest is stale");
        }
    }

    /**
     * What goes in a single-valued column of the catalog table: the value itself for one file, and for several files
     * {@code key: value} of each source, ordered by key. It must fit the column, which the database would otherwise
     * report as a bare SQL error.
     */
    private static String joined(String where, String field, int maxChars, CatalogManifest.Entry entry,
                                 Function<CatalogManifest.Source, String> value) {
        String text = entry.keyed()
                ? entry.sources().stream().map(source -> source.key() + ": " + value.apply(source))
                        .collect(Collectors.joining(SOURCE_SEPARATOR))
                : value.apply(entry.sources().get(0));
        if (text.codePointCount(0, text.length()) > maxChars) {
            throw new CatalogFormatException(where + ": " + field + " of the sources is " + text.length()
                    + " characters together but the catalog table holds " + maxChars + "; shorten it");
        }
        return text;
    }

    /**
     * The value stored as the file hash: the hash of the file for one file, and for several a SHA-256 over the lines
     * {@code <key> <hash>} ordered by key, so a change of any source changes it.
     */
    private static String filesHash(CatalogManifest.Entry entry) {
        if (!entry.keyed()) {
            return entry.sources().get(0).sha256();
        }
        StringBuilder text = new StringBuilder(FILES_FORMAT).append('\n');
        for (CatalogManifest.Source source : entry.sources()) {
            text.append(source.key()).append(' ').append(source.sha256()).append('\n');
        }
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The next line without its LF or CR LF, or null at the end of the file. Only LF ends a line (a lone CR does not),
     * so the line numbers are those of an editor that counts LF.
     */
    private static String readLine(Reader reader, StringBuilder buffer, String where) throws IOException {
        buffer.setLength(0);
        boolean any = false;
        for (int c = reader.read(); c >= 0; c = reader.read()) {
            any = true;
            if (c == '\n') {
                break;
            }
            buffer.append((char) c);
            if (buffer.length() > MAX_LINE_CHARS) {
                throw new CatalogFormatException(where + ": the line is longer than " + MAX_LINE_CHARS + " characters");
            }
        }
        if (!any) {
            return null;
        }
        int length = buffer.length();
        if (length > 0 && buffer.charAt(length - 1) == '\r') {
            buffer.setLength(length - 1);
        }
        return buffer.toString();
    }

    private static CatalogRecord record(String where, String line, TextNormalizer normalizer, String sourceKey) {
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
        String storedId = sourceKey == null ? sourceId : sourceKey + ":" + sourceId;
        return new CatalogRecord(display, normalized, status, niceClasses, storedId, commonWord);
    }

    private static String requiredText(String where, JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new CatalogFormatException(where + ": '" + key + "' is missing or not a non-empty string");
        }
        return value.asText();
    }

    /** Code point order: what {@code COLLATE "C"} gives in a UTF-8 PostgreSQL database. */
    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static List<PreparedCatalog.Entry> merge(List<CatalogRecord> kept) {
        Comparator<String> codePoints = CatalogImport::compareCodePoints;
        Map<String, List<CatalogRecord>> byNormalized = new TreeMap<>(codePoints);
        for (CatalogRecord record : kept) {
            byNormalized.computeIfAbsent(record.normalized(), k -> new ArrayList<>()).add(record);
        }
        List<PreparedCatalog.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, List<CatalogRecord>> group : byNormalized.entrySet()) {
            CatalogRecord display = group.getValue().stream()
                    .min(Comparator.comparing(CatalogRecord::display, codePoints)
                            .thenComparing(CatalogRecord::sourceId, codePoints)).orElseThrow();
            Set<Integer> classes = new TreeSet<>();
            Set<String> sourceIds = new TreeSet<>(codePoints);
            MarkStatus status = null;
            boolean common = false;
            for (CatalogRecord record : group.getValue()) {
                classes.addAll(record.classes());
                sourceIds.add(record.sourceId());
                common |= record.commonWord();
                if (status == null || record.status().compareTo(status) < 0) {
                    status = record.status();
                }
            }
            entries.add(new PreparedCatalog.Entry(new CatalogMark(group.getKey(), display.display(),
                    List.copyOf(classes), status, common, List.copyOf(sourceIds))));
        }
        return entries;
    }

    /** A digest of everything that identifies the import, so any difference is a different catalog version. */
    static String digest(CatalogManifest.Entry entry, String filtersJson, NormalizationIdentity identity) {
        if (entry.keyed()) {
            return digestOfSources(entry, filtersJson, identity);
        }
        CatalogManifest.Source source = entry.sources().get(0);
        String text = IDENTITY_FORMAT + "\n"
                + "label=" + entry.version() + "\n"
                + "source_name=" + source.sourceName() + "\n"
                + "url=" + source.url() + "\n"
                + "license=" + source.license() + "\n"
                + "source_date=" + source.sourceDate() + "\n"
                + "file_sha256=" + source.sha256() + "\n"
                + "format_version=" + entry.formatVersion() + "\n"
                + "filters=" + filtersJson + "\n"
                + "pack_id=" + identity.packId() + "\n"
                + "normalization_version=" + identity.normalizationVersion() + "\n"
                + "normalization_hash=" + identity.normalizationHash() + "\n";
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Every source, ordered by key (the order in the manifest changes nothing), as one JSON array of escaped text. */
    private static String digestOfSources(CatalogManifest.Entry entry, String filtersJson,
                                          NormalizationIdentity identity) {
        List<Map<String, String>> sources = new ArrayList<>();
        for (CatalogManifest.Source source : entry.sources()) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("source_key", source.key());
            fields.put("source_name", source.sourceName());
            fields.put("url", source.url());
            fields.put("license", source.license());
            fields.put("source_date", source.sourceDate().toString());
            fields.put("file_sha256", source.sha256());
            sources.add(fields);
        }
        String sourcesJson;
        try {
            sourcesJson = JSON.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the sources of the catalog identity", e);
        }
        String text = IDENTITY_FORMAT_SOURCES + "\n"
                + "label=" + entry.version() + "\n"
                + "sources=" + sourcesJson + "\n"
                + "format_version=" + entry.formatVersion() + "\n"
                + "filters=" + filtersJson + "\n"
                + "pack_id=" + identity.packId() + "\n"
                + "normalization_version=" + identity.normalizationVersion() + "\n"
                + "normalization_hash=" + identity.normalizationHash() + "\n";
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** SHA-256 of a file's bytes as lower-case hex: what the manifest holds. */
    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }
}
