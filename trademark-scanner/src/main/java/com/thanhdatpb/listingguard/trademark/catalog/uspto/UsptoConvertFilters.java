package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What the USPTO converter keeps, read from a versioned YAML file (ADR-009, PR A2c): the Nice classes, the status codes
 * that count as live, whether a mark needs words and how a date is read from a file name. Strict like the status map
 * and the rulesets: an unknown or missing key, a duplicate key, a bad value or a {@code source.status} other than
 * {@code unverified} fails at load time. Everything in it is the maintainer's choice and is not a USPTO statement or a
 * legal conclusion. {@code contentHash} is the SHA-256 of the text with LF line endings and no BOM; the converter reports
 * it.
 */
public final class UsptoConvertFilters {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private static final Set<String> ROOT_KEYS = Set.of("version", "format", "checked_at", "source", "nice_classes",
            "live_status_codes", "text_only", "file_name_dates");
    private static final Set<String> SOURCE_KEYS = Set.of("status", "note");
    private static final Pattern STATUS_CODE = Pattern.compile("\\d{3}");
    private static final Set<String> DATE_GROUPS = Set.of("year", "yy", "month", "day");

    private final String version;
    private final LocalDate checkedAt;
    private final String contentHash;
    private final SortedSet<Integer> niceClasses;
    private final SortedSet<String> liveStatusCodes;
    private final boolean textOnly;
    private final List<Pattern> fileNameDates;

    private UsptoConvertFilters(String version, LocalDate checkedAt, String contentHash, SortedSet<Integer> niceClasses,
                                SortedSet<String> liveStatusCodes, boolean textOnly, List<Pattern> fileNameDates) {
        this.version = version;
        this.checkedAt = checkedAt;
        this.contentHash = contentHash;
        this.niceClasses = Collections.unmodifiableSortedSet(niceClasses);
        this.liveStatusCodes = Collections.unmodifiableSortedSet(liveStatusCodes);
        this.textOnly = textOnly;
        this.fileNameDates = List.copyOf(fileNameDates);
    }

    public static UsptoConvertFilters loadClasspath(String path) {
        String resource = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = UsptoConvertFilters.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UsptoConvertFiltersException("Conversion filters not found on classpath: " + path);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UsptoConvertFiltersException("Cannot read the conversion filters " + path, e);
        }
    }

    public static UsptoConvertFilters parse(String yamlText) {
        String text = normalize(yamlText);
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new UsptoConvertFiltersException("Invalid conversion filters YAML: " + e.getOriginalMessage(), e);
        }
        object(root, "the file", ROOT_KEYS);
        String version = text(root, "version");
        if (!version.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new UsptoConvertFiltersException("version must match [a-z0-9][a-z0-9._-]{0,63}, got '" + version + "'");
        }
        String format = text(root, "format");
        if (!UsptoStatusMap.FORMAT.equals(format)) {
            throw new UsptoConvertFiltersException("format must be '" + UsptoStatusMap.FORMAT + "', got '" + format + "'");
        }
        LocalDate checkedAt;
        try {
            checkedAt = LocalDate.parse(text(root, "checked_at"));
        } catch (DateTimeParseException e) {
            throw new UsptoConvertFiltersException("checked_at must be a date yyyy-MM-dd");
        }
        JsonNode source = root.get("source");
        object(source, "source", SOURCE_KEYS);
        if (!"unverified".equals(text(source, "status"))) {
            throw new UsptoConvertFiltersException("source.status must be 'unverified': nothing in these filters is "
                    + "checked against a real file or by the USPTO");
        }
        text(source, "note");

        SortedSet<Integer> classes = new TreeSet<>();
        for (JsonNode node : array(root, "nice_classes")) {
            if (!node.isInt() || node.intValue() < 1 || node.intValue() > 45) {
                throw new UsptoConvertFiltersException("nice_classes: each class is an integer from 1 to 45, got "
                        + node);
            }
            if (!classes.add(node.intValue())) {
                throw new UsptoConvertFiltersException("nice_classes: class " + node.intValue() + " is listed twice");
            }
        }
        SortedSet<String> codes = new TreeSet<>();
        for (JsonNode node : array(root, "live_status_codes")) {
            if (!node.isTextual() || !STATUS_CODE.matcher(node.asText()).matches()) {
                throw new UsptoConvertFiltersException("live_status_codes: each code is three digits written as a quoted "
                        + "string, got " + node);
            }
            if (!codes.add(node.asText())) {
                throw new UsptoConvertFiltersException("live_status_codes: code " + node.asText() + " is listed twice");
            }
        }
        JsonNode textOnly = root.get("text_only");
        if (textOnly == null || !textOnly.isBoolean()) {
            throw new UsptoConvertFiltersException("text_only must be true or false");
        }
        List<Pattern> patterns = new ArrayList<>();
        for (JsonNode node : array(root, "file_name_dates")) {
            if (!node.isTextual()) {
                throw new UsptoConvertFiltersException("file_name_dates: each pattern is a string, got " + node);
            }
            patterns.add(datePattern(node.asText()));
        }
        return new UsptoConvertFilters(version, checkedAt, sha256(text), classes, codes, textOnly.booleanValue(),
                patterns);
    }

    private static Pattern datePattern(String regex) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new UsptoConvertFiltersException("file_name_dates: '" + regex + "' is not a regular expression: "
                    + e.getDescription());
        }
        Set<String> groups = pattern.namedGroups().keySet();
        if (!DATE_GROUPS.containsAll(groups) || !groups.contains("month") || !groups.contains("day")
                || groups.contains("year") == groups.contains("yy")) {
            throw new UsptoConvertFiltersException("file_name_dates: '" + regex + "' must have the named groups month "
                    + "and day and exactly one of year (four digits) and yy (two digits), and no other group name; it has "
                    + groups);
        }
        return pattern;
    }

    /** True when {@code code} is one of the live status codes. */
    public boolean isLive(String code) {
        return liveStatusCodes.contains(code);
    }

    /** True when at least one of the classes is in the list. */
    public boolean includesAnyClass(Collection<Integer> classes) {
        for (int niceClass : classes) {
            if (niceClasses.contains(niceClass)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The date in a file name, read with the patterns in order; the first pattern that matches and gives a real date
     * wins. Only the last segment of the name is read. Null when there is none.
     */
    public LocalDate fileNameDate(String fileName) {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = fileName.substring(slash + 1);
        for (Pattern pattern : fileNameDates) {
            Matcher matcher = pattern.matcher(base);
            if (!matcher.find()) {
                continue;
            }
            try {
                int year = pattern.namedGroups().containsKey("year") ? Integer.parseInt(matcher.group("year"))
                        : 2000 + Integer.parseInt(matcher.group("yy"));
                return LocalDate.of(year, Integer.parseInt(matcher.group("month")), Integer.parseInt(matcher.group("day")));
            } catch (DateTimeException | NumberFormatException ignored) {
                // not a real date: try the next pattern
            }
        }
        return null;
    }

    /**
     * Checks the live codes against the status map the converter will use: each one must be listed there and must not
     * be {@code DROP} (a code that carries no status of the mark cannot be live).
     */
    public void requireCodesIn(UsptoStatusMap statusMap) {
        for (String code : liveStatusCodes) {
            if (!(statusMap.outcome(code) instanceof UsptoStatusMap.Mapped)) {
                throw new UsptoConvertFiltersException("live_status_codes: code " + code + " is not a status of a mark in "
                        + "status map " + statusMap.version() + " (it is dropped there or not listed)");
            }
        }
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

    public SortedSet<Integer> niceClasses() {
        return niceClasses;
    }

    public SortedSet<String> liveStatusCodes() {
        return liveStatusCodes;
    }

    public boolean textOnly() {
        return textOnly;
    }

    /** The node is a map holding exactly {@code keys}: none unknown, none missing. */
    private static void object(JsonNode node, String where, Set<String> keys) {
        if (node == null || !node.isObject()) {
            throw new UsptoConvertFiltersException(where + " must be a map");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!keys.contains(name)) {
                throw new UsptoConvertFiltersException(where + ": unknown key '" + name + "'");
            }
        }
        for (String key : keys) {
            if (!node.has(key)) {
                throw new UsptoConvertFiltersException(where + ": missing key '" + key + "'");
            }
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new UsptoConvertFiltersException("'" + key + "' must be a non-empty string");
        }
        return value.asText();
    }

    private static JsonNode array(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new UsptoConvertFiltersException("'" + key + "' must be a non-empty list");
        }
        return value;
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
