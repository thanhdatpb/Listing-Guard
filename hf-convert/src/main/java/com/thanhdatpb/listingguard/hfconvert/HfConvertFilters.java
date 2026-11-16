package com.thanhdatpb.listingguard.hfconvert;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What the {@code hf-convert} job keeps, read from a versioned YAML file (ADR-030). Strict like the USPTO converter's
 * filters: an unknown, missing or duplicate key, a bad value or a {@code source.status} other than {@code unverified}
 * fails at load time. {@code contentHash} is the SHA-256 of the text with LF line endings and no BOM; the report names it.
 */
public final class HfConvertFilters {

    public static final String DEFAULT_RESOURCE = "hf/hf-convert-filters-2026-11.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> ROOT_KEYS = Set.of("version", "dataset", "checked_at", "source", "status_lookup",
            "registered_status_codes", "pending_status_codes", "nice_classes", "text_only", "min_mark_chars", "min_year");
    private static final Set<String> SOURCE_KEYS = Set.of("status", "note");
    private static final Set<String> LOOKUP_KEYS = Set.of("url", "upstream", "read_at");
    private static final Pattern STATUS_CODE = Pattern.compile("\\d{3}");
    private static final Pattern VERSION = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern DATASET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    /** The lowest and highest {@code min_year} accepted; the dataset starts in 1884. */
    static final int MIN_YEAR_FLOOR = 1800;
    static final int MIN_YEAR_CEILING = 2100;

    private final String version;
    private final String dataset;
    private final LocalDate checkedAt;
    private final String contentHash;
    private final String lookupUrl;
    private final String lookupUpstream;
    private final SortedMap<String, MarkStatus> statusCodes;
    private final SortedSet<Integer> niceClasses;
    private final boolean textOnly;
    private final int minMarkChars;
    private final Integer minYear;

    private HfConvertFilters(String version, String dataset, LocalDate checkedAt, String contentHash, String lookupUrl,
                             String lookupUpstream, SortedMap<String, MarkStatus> statusCodes,
                             SortedSet<Integer> niceClasses, boolean textOnly, int minMarkChars, Integer minYear) {
        this.version = version;
        this.dataset = dataset;
        this.checkedAt = checkedAt;
        this.contentHash = contentHash;
        this.lookupUrl = lookupUrl;
        this.lookupUpstream = lookupUpstream;
        this.statusCodes = Collections.unmodifiableSortedMap(statusCodes);
        this.niceClasses = Collections.unmodifiableSortedSet(niceClasses);
        this.textOnly = textOnly;
        this.minMarkChars = minMarkChars;
        this.minYear = minYear;
    }

    public static HfConvertFilters loadDefault() {
        return loadClasspath(DEFAULT_RESOURCE);
    }

    public static HfConvertFilters loadClasspath(String path) {
        String resource = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = HfConvertFilters.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new HfConvertException("Conversion filters not found on the classpath: " + path);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new HfConvertException("Cannot read the conversion filters " + path, e);
        }
    }

    public static HfConvertFilters load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new HfConvertException("Cannot read the conversion filters " + file, e);
        }
    }

    public static HfConvertFilters parse(String yamlText) {
        String text = normalize(yamlText);
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new HfConvertException("Invalid conversion filters YAML: " + e.getOriginalMessage(), e);
        }
        object(root, "the file", ROOT_KEYS);
        String version = text(root, "version");
        if (!VERSION.matcher(version).matches()) {
            throw new HfConvertException("version must match " + VERSION + ", got '" + version + "'");
        }
        String dataset = text(root, "dataset");
        if (!DATASET.matcher(dataset).matches()) {
            throw new HfConvertException("dataset must be <owner>/<name> of a Hugging Face dataset, got '" + dataset + "'");
        }
        LocalDate checkedAt = date(root, "checked_at");
        JsonNode source = root.get("source");
        object(source, "source", SOURCE_KEYS);
        if (!"unverified".equals(text(source, "status"))) {
            throw new HfConvertException("source.status must be 'unverified': nothing in these filters is checked by the "
                    + "USPTO");
        }
        text(source, "note");
        JsonNode lookup = root.get("status_lookup");
        object(lookup, "status_lookup", LOOKUP_KEYS);
        String lookupUrl = text(lookup, "url");
        String lookupUpstream = text(lookup, "upstream");
        date(lookup, "read_at");

        SortedMap<String, MarkStatus> codes = new TreeMap<>();
        statusCodes(root, "registered_status_codes", MarkStatus.REGISTERED, codes);
        statusCodes(root, "pending_status_codes", MarkStatus.PENDING, codes);

        SortedSet<Integer> classes = new TreeSet<>();
        for (JsonNode node : array(root, "nice_classes")) {
            if (!node.isInt() || node.intValue() < 1 || node.intValue() > 45) {
                throw new HfConvertException("nice_classes: each class is an integer from 1 to 45, got " + node);
            }
            if (!classes.add(node.intValue())) {
                throw new HfConvertException("nice_classes: class " + node.intValue() + " is listed twice");
            }
        }
        JsonNode textOnly = root.get("text_only");
        if (!textOnly.isBoolean()) {
            throw new HfConvertException("text_only must be true or false");
        }
        JsonNode minChars = root.get("min_mark_chars");
        if (!minChars.isInt() || minChars.intValue() < 1 || minChars.intValue() > 50) {
            throw new HfConvertException("min_mark_chars must be an integer from 1 to 50, got " + minChars);
        }
        JsonNode minYear = root.get("min_year");
        Integer year = null;
        if (!minYear.isNull()) {
            if (!minYear.isInt()) {
                throw new HfConvertException("min_year must be null or a year, got " + minYear);
            }
            year = checkYear(minYear.intValue());
        }
        return new HfConvertFilters(version, dataset, checkedAt, sha256(text), lookupUrl, lookupUpstream, codes,
                classes, textOnly.booleanValue(), minChars.intValue(), year);
    }

    /** The same filters with another {@code min_year}; the content hash stays the file's (the report names both). */
    public HfConvertFilters withMinYear(Integer minYear) {
        Integer year = minYear == null ? null : checkYear(minYear);
        return new HfConvertFilters(version, dataset, checkedAt, contentHash, lookupUrl, lookupUpstream, statusCodes,
                niceClasses, textOnly, minMarkChars, year);
    }

    static int checkYear(int year) {
        if (year < MIN_YEAR_FLOOR || year > MIN_YEAR_CEILING) {
            throw new HfConvertException("min_year must be from " + MIN_YEAR_FLOOR + " to " + MIN_YEAR_CEILING
                    + ", got " + year);
        }
        return year;
    }

    private static void statusCodes(JsonNode root, String key, MarkStatus status, SortedMap<String, MarkStatus> codes) {
        for (JsonNode node : array(root, key)) {
            if (!node.isTextual() || !STATUS_CODE.matcher(node.asText()).matches()) {
                throw new HfConvertException(key + ": each code is three digits written as a quoted string, got " + node);
            }
            MarkStatus earlier = codes.putIfAbsent(node.asText(), status);
            if (earlier != null) {
                throw new HfConvertException(key + ": code " + node.asText() + " is listed twice"
                        + (earlier == status ? "" : " (also as " + earlier + ")"));
            }
        }
    }

    /** The catalog status of a live code; null for every other code (dead, indifferent or unknown). */
    public MarkStatus statusOf(String code) {
        return code == null ? null : statusCodes.get(code);
    }

    public String version() {
        return version;
    }

    /** The Hugging Face dataset these filters were written for, {@code <owner>/<name>}. */
    public String dataset() {
        return dataset;
    }

    public LocalDate checkedAt() {
        return checkedAt;
    }

    public String contentHash() {
        return contentHash;
    }

    public String lookupUrl() {
        return lookupUrl;
    }

    public String lookupUpstream() {
        return lookupUpstream;
    }

    /** Every live code and the status it is written with. */
    public SortedMap<String, MarkStatus> statusCodes() {
        return statusCodes;
    }

    public SortedSet<Integer> niceClasses() {
        return niceClasses;
    }

    public boolean textOnly() {
        return textOnly;
    }

    public int minMarkChars() {
        return minMarkChars;
    }

    /** Null when every year is kept. */
    public Integer minYear() {
        return minYear;
    }

    private static String normalize(String text) {
        String withoutBom = text.startsWith("﻿") ? text.substring(1) : text;
        return withoutBom.replace("\r\n", "\n");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The node is a map holding exactly {@code keys}: none unknown, none missing. */
    private static void object(JsonNode node, String where, Set<String> keys) {
        if (node == null || !node.isObject()) {
            throw new HfConvertException(where + " must be a map");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!keys.contains(name)) {
                throw new HfConvertException(where + ": unknown key '" + name + "'");
            }
        }
        for (String key : keys) {
            if (!node.has(key)) {
                throw new HfConvertException(where + ": missing key '" + key + "'");
            }
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new HfConvertException("'" + key + "' must be a non-empty string");
        }
        return value.asText();
    }

    private static LocalDate date(JsonNode node, String key) {
        try {
            return LocalDate.parse(text(node, key));
        } catch (DateTimeParseException e) {
            throw new HfConvertException(key + " must be a date yyyy-MM-dd");
        }
    }

    private static JsonNode array(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new HfConvertException(key + " must be a non-empty list");
        }
        return value;
    }
}
