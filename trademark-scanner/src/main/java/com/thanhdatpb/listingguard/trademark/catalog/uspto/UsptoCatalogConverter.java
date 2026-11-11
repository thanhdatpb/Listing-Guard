package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Converts one or more USPTO Trademark Applications Daily XML files (TDXF, DTD V2.0) to one {@code jsonl-1} file, keeping
 * for each serial number only its <b>latest</b> record and only the marks the filters keep (ADR-009, PR A2c). It is
 * <b>unverified against real data</b>: it follows the public V2.0 DTD and element documentation, which ADR-009 lists.
 *
 * <p><b>The order.</b> Two records of one serial number are ordered by (1) {@code case-file/transaction-date}, (2) the
 * date in the file name ({@link UsptoConvertFilters#fileNameDate}; none sorts first), (3) the file name, by code point,
 * and (4) the position of the case file in its file; the later one wins. The files are sorted by (2) and (3) before they
 * are read, so the result does not depend on the order in which they are passed.
 *
 * <p><b>Two passes</b>, both streaming: pass 1 reads every file and keeps the key of the latest record of each serial
 * number ({@link SerialIndex}); pass 2 reads every file again and writes a record only when it is that latest record
 * and the filters keep it. A file whose SHA-256 differs between the passes stops the run. A record whose status code is
 * dropped or unmapped, or which is a record error, does not take part in the order, so it cannot replace an earlier one.
 *
 * <p><b>The checks of a record</b>, which counts it once under the first it fails: a record error
 * ({@code missing_serial}, {@code invalid_serial}, {@code missing_transaction_date}, {@code invalid_transaction_date}),
 * {@code status_dropped:<code>} or {@code status_unmapped:<code>}, superseded by a later record, not live
 * ({@code deadRemoved}), the drawing code when the filters want text ({@code missing_drawing_code}, {@code logo_only},
 * {@code no_drawing}, {@code unknown_drawing_code}), {@code blank_mark}, {@code mark_too_long}, the classes
 * ({@code no_valid_nice_class}, {@code class_not_included}), then {@code mark_empty_after_normalization} and
 * {@code mark_too_long_after_normalization} (the import refuses a whole catalog for either, so they are dropped here).
 *
 * <p><b>Problems.</b> A file that cannot be read (not well-formed, not a daily applications file, another version) is
 * left out whole; a record error is counted and listed with its file and place. Not strict, the run goes on and the report
 * is {@link Completeness#INCOMPLETE}; strict, the first problem stops it with that message. The messages and the report
 * hold no mark and no serial number.
 */
public final class UsptoCatalogConverter {

    public static final String EXPECTED_VERSION_NO = UsptoXmlReader.EXPECTED_VERSION_NO;

    public static final String MISSING_SERIAL = "missing_serial";
    public static final String INVALID_SERIAL = "invalid_serial";
    public static final String MISSING_TRANSACTION_DATE = "missing_transaction_date";
    public static final String INVALID_TRANSACTION_DATE = "invalid_transaction_date";
    public static final String MISSING_DRAWING_CODE = "missing_drawing_code";
    public static final String LOGO_ONLY = "logo_only";
    public static final String NO_DRAWING = "no_drawing";
    public static final String UNKNOWN_DRAWING_CODE = "unknown_drawing_code";
    public static final String BLANK_MARK = "blank_mark";
    public static final String MARK_TOO_LONG = "mark_too_long";
    public static final String NO_VALID_NICE_CLASS = "no_valid_nice_class";
    public static final String CLASS_NOT_INCLUDED = "class_not_included";
    public static final String MARK_EMPTY_AFTER_NORMALIZATION = "mark_empty_after_normalization";
    public static final String MARK_TOO_LONG_AFTER_NORMALIZATION = "mark_too_long_after_normalization";
    public static final String STATUS_DROPPED = "status_dropped:";
    public static final String STATUS_UNMAPPED = "status_unmapped:";

    /** The most files in one run: the file's place is 10 bits of the order key. */
    public static final int MAX_FILES = 1023;
    /** The most case files in one file: the position is 26 bits of the order key. */
    public static final long MAX_RECORDS_PER_FILE = 1L << 26;

    private static final int BUFFER_CHARS = 64 * 1024;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final Pattern SERIAL = Pattern.compile("\\d{8}");
    private static final Pattern DATE = Pattern.compile("\\d{8}");
    private static final Pattern STATUS_CODE = Pattern.compile("\\d{3}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One input: a name (a path inside {@code catalogs/raw}, used in messages and for the order) and its bytes. */
    public interface Source {
        String name();

        /** A new stream over the whole file; it is opened twice, once for each pass, and closed by the converter. */
        InputStream open() throws IOException;

        static Source file(String name, Path path) {
            return new Source() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public InputStream open() throws IOException {
                    return Files.newInputStream(path);
                }
            };
        }
    }

    /**
     * @param allowOtherVersion read a file that declares a {@code version-no} other than 2.0
     * @param strict            stop at the first problem instead of leaving the file or counting the record
     * @param maxDistinctSerials the most distinct serial numbers held in memory; more stops the run
     */
    public record Options(boolean allowOtherVersion, boolean strict, int maxDistinctSerials) {
        public static final int DEFAULT_MAX_DISTINCT_SERIALS = 12_000_000;

        public Options {
            if (maxDistinctSerials < 1) {
                throw new IllegalArgumentException("maxDistinctSerials must be at least 1.");
            }
        }

        public static Options defaults() {
            return new Options(false, false, DEFAULT_MAX_DISTINCT_SERIALS);
        }
    }

    private final UsptoStatusMap statusMap;
    private final UsptoConvertFilters filters;
    private final TextNormalizer normalizer;
    private final Options options;

    /**
     * @param normalizer the normalizer of the active trademark ruleset: the import normalizes each mark with it and
     *                   refuses a catalog for a mark that becomes empty or too long
     * @throws UsptoConvertFiltersException if a live status code of the filters is not a status of a mark in the map
     */
    public UsptoCatalogConverter(UsptoStatusMap statusMap, UsptoConvertFilters filters, TextNormalizer normalizer,
                                 Options options) {
        filters.requireCodesIn(statusMap);
        this.statusMap = statusMap;
        this.filters = filters;
        this.normalizer = normalizer;
        this.options = options;
    }

    /**
     * Reads the files and writes {@code jsonl-1} (UTF-8, LF) to {@code jsonl}. The stream is not closed; the output is
     * flushed. On an exception the output holds a partial result and must be discarded.
     *
     * @throws IllegalArgumentException if there is no file, more than {@link #MAX_FILES}, or two with the same name
     * @throws UsptoConversionException strict and a problem; no file could be read; a file changed between the passes;
     *                                  too many distinct serial numbers
     * @throws IOException              if a stream fails
     */
    public ConversionReport convert(List<Source> sources, OutputStream jsonl) throws IOException {
        List<Source> ordered = order(sources);
        SerialIndex index = new SerialIndex(options.maxDistinctSerials());
        List<FileState> files = new ArrayList<>();
        try {
            for (int i = 0; i < ordered.size(); i++) {
                files.add(firstPass(ordered.get(i), i, index));
            }
            if (files.stream().noneMatch(f -> f.failure == null)) {
                throw new UsptoConversionException("No file could be read: " + firstFailures(files));
            }
            return secondPass(files, index, jsonl);
        } catch (Stop stop) {
            throw new UsptoConversionException(stop.getMessage());
        }
    }

    // --- the order of the files ---

    private List<Source> order(List<Source> sources) {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("There is no file to convert.");
        }
        if (sources.size() > MAX_FILES) {
            throw new IllegalArgumentException("At most " + MAX_FILES + " files can be converted in one run, got "
                    + sources.size() + ".");
        }
        Set<String> names = new HashSet<>();
        for (Source source : sources) {
            if (!names.add(source.name())) {
                throw new IllegalArgumentException("The file " + source.name() + " is given twice.");
            }
        }
        List<Source> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.<Source, LocalDate>comparing(s -> {
            LocalDate date = filters.fileNameDate(s.name());
            return date == null ? LocalDate.MIN : date;
        }).thenComparing(s -> s.name(), UsptoCatalogConverter::compareCodePoints));
        return ordered;
    }

    private static int compareCodePoints(String a, String b) {
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

    // --- what a record is ---

    /** What a record is for the order: a problem, a record that says nothing about the status, or a candidate. */
    private sealed interface Classified permits Problem, NoStatus, Eligible {
    }

    private record Problem(String code) implements Classified {
    }

    private record NoStatus(String reason) implements Classified {
    }

    private record Eligible(int serial, String serialText, int date, String statusCode, MarkStatus status)
            implements Classified {
    }

    private Classified classify(UsptoXmlReader.RawRecord record) {
        String serial = record.serial() == null ? "" : record.serial().trim();
        if (serial.isEmpty()) {
            return new Problem(MISSING_SERIAL);
        }
        if (!SERIAL.matcher(serial).matches()) {
            return new Problem(INVALID_SERIAL);
        }
        String dateText = record.transactionDate() == null ? "" : record.transactionDate().trim();
        if (dateText.isEmpty()) {
            return new Problem(MISSING_TRANSACTION_DATE);
        }
        int date = parseDate(dateText);
        if (date == 0) {
            return new Problem(INVALID_TRANSACTION_DATE);
        }
        String code = record.status() == null ? "" : record.status().trim();
        String codeKey = code.isEmpty() ? "missing" : STATUS_CODE.matcher(code).matches() ? code : "invalid";
        UsptoStatusMap.Outcome outcome = "missing".equals(codeKey) || "invalid".equals(codeKey)
                ? new UsptoStatusMap.Unmapped() : statusMap.outcome(code);
        if (outcome instanceof UsptoStatusMap.Dropped) {
            return new NoStatus(STATUS_DROPPED + codeKey);
        }
        if (!(outcome instanceof UsptoStatusMap.Mapped mapped)) {
            return new NoStatus(STATUS_UNMAPPED + codeKey);
        }
        return new Eligible(Integer.parseInt(serial), serial, date, code, mapped.status());
    }

    /** {@code yyyyMMdd} as a number, or 0 when it is not a real date from the year 1000 on. */
    private static int parseDate(String text) {
        if (!DATE.matcher(text).matches()) {
            return 0;
        }
        int year = Integer.parseInt(text.substring(0, 4));
        int month = Integer.parseInt(text.substring(4, 6));
        int day = Integer.parseInt(text.substring(6, 8));
        try {
            LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return 0;
        }
        return year < 1000 ? 0 : year * 10_000 + month * 100 + day;
    }

    /** The order key: the transaction date, then the file's place, then the record's place; larger is later. */
    private static long key(int date, int fileIndex, long position) {
        return ((long) date << 36) | ((long) fileIndex << 26) | (position - 1);
    }

    private static String describe(UsptoXmlReader.RawRecord record, String code) {
        String what = switch (code) {
            case MISSING_SERIAL -> "has no serial-number";
            case INVALID_SERIAL -> "has a serial-number that is not eight digits";
            case MISSING_TRANSACTION_DATE -> "has no transaction-date, which the latest-transaction rule needs";
            default -> "has a transaction-date that is not a real date in the format YYYYMMDD";
        };
        return "record " + record.position() + " (line " + record.line() + ", column " + record.column() + ") " + what
                + " [" + code + "]";
    }

    // --- pass 1 ---

    /** What is known of one file after the first pass. */
    private static final class FileState {
        private final Source source;
        private final String name;
        private final UsptoXmlReader.FileScan scan;
        private final String failure;
        private final List<ConversionReport.RecordError> details;

        private FileState(Source source, UsptoXmlReader.FileScan scan, String failure,
                          List<ConversionReport.RecordError> details) {
            this.source = source;
            this.name = source.name();
            this.scan = scan;
            this.failure = failure;
            this.details = details;
        }
    }

    /** Stops the whole run with a message; not a problem of one file. */
    private static final class Stop extends RuntimeException {
        Stop(String message) {
            super(message, null, false, false);
        }
    }

    private FileState firstPass(Source source, int fileIndex, SerialIndex index) throws IOException {
        String name = source.name();
        Candidates candidates = new Candidates();
        List<ConversionReport.RecordError> details = new ArrayList<>();
        UsptoXmlReader.FileScan scan;
        try (InputStream in = new BufferedInputStream(source.open(), BUFFER_BYTES)) {
            scan = UsptoXmlReader.read(in, options.allowOtherVersion(), record -> {
                Classified classified = classify(record);
                if (classified instanceof Problem problem) {
                    if (options.strict()) {
                        throw new Stop(name + ": " + describe(record, problem.code()));
                    }
                    if (details.size() < ConversionReport.MAX_ERROR_DETAILS) {
                        details.add(new ConversionReport.RecordError(name, record.position(), record.line(),
                                record.column(), problem.code()));
                    }
                } else if (classified instanceof Eligible eligible) {
                    if (record.position() > MAX_RECORDS_PER_FILE) {
                        throw new Stop(name + ": more than " + MAX_RECORDS_PER_FILE + " records in one file.");
                    }
                    if (candidates.size() >= options.maxDistinctSerials()) {
                        throw new Stop(name + ": more than " + options.maxDistinctSerials() + " usable records in one "
                                + "file, the limit of listing-guard.trademark.uspto.max-distinct-serials.");
                    }
                    candidates.add(eligible.serial(), key(eligible.date(), fileIndex, record.position()));
                }
            });
        } catch (UsptoConversionException e) {
            String message = name + ": " + e.getMessage();
            if (options.strict()) {
                throw new UsptoConversionException(message, e);
            }
            return new FileState(source, null, message, List.of());
        }
        candidates.mergeInto(index);
        return new FileState(source, scan, null, details);
    }

    private static String firstFailures(List<FileState> files) {
        List<String> messages = new ArrayList<>();
        for (FileState file : files) {
            if (file.failure != null && messages.size() < 3) {
                messages.add(file.failure);
            }
        }
        return String.join(" | ", messages);
    }

    /** The candidates of one file: each serial number and key, held until the whole file is known to be readable. */
    private static final class Candidates {
        private int[] serials = new int[1024];
        private long[] keys = new long[1024];
        private int size;

        int size() {
            return size;
        }

        void add(int serial, long key) {
            if (size == keys.length) {
                serials = java.util.Arrays.copyOf(serials, size * 2);
                keys = java.util.Arrays.copyOf(keys, size * 2);
            }
            serials[size] = serial;
            keys[size] = key;
            size++;
        }

        void mergeInto(SerialIndex index) {
            for (int i = 0; i < size; i++) {
                index.offer(serials[i], keys[i]);
            }
        }
    }

    // --- pass 2 ---

    private ConversionReport secondPass(List<FileState> files, SerialIndex index, OutputStream jsonl) throws IOException {
        MessageDigest outDigest = newDigest();
        CountingOutput out = new CountingOutput(new DigestOutputStream(jsonl, outDigest));
        Tally tally = new Tally(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), BUFFER_CHARS),
                index);
        for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
            FileState file = files.get(fileIndex);
            if (file.failure != null) {
                continue;
            }
            tally.file(fileIndex); // the place of a file in this list is the place pass 1 gave it in the order key
            UsptoXmlReader.FileScan scan;
            try (InputStream in = new BufferedInputStream(file.source.open(), BUFFER_BYTES)) {
                scan = UsptoXmlReader.read(in, options.allowOtherVersion(), tally::record);
            } catch (UsptoConversionException e) {
                throw new UsptoConversionException(file.name + ": " + e.getMessage(), e);
            }
            if (!scan.sha256().equals(file.scan.sha256()) || scan.records() != file.scan.records()) {
                throw new UsptoConversionException(file.name + ": the file changed while it was converted.");
            }
        }
        tally.writer.flush();
        return tally.report(files, index.size(), out.count, HexFormat.of().formatHex(outDigest.digest()));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private final class Tally {
        private final Writer writer;
        private final SerialIndex index;
        private final Map<String, Long> filtered = new TreeMap<>();
        private final Map<String, Long> errors = new TreeMap<>();
        private final Map<String, Long> deadByStatus = new TreeMap<>();
        private int fileIndex;
        private long recordsRead;
        private long recordsWritten;
        private long recordErrors;
        private long superseded;
        private long deadRemoved;
        private long ignoredClassCodes;

        Tally(Writer writer, SerialIndex index) {
            this.writer = writer;
            this.index = index;
        }

        void file(int fileIndex) {
            this.fileIndex = fileIndex;
        }

        void record(UsptoXmlReader.RawRecord record) throws IOException {
            recordsRead++;
            Classified classified = classify(record);
            if (classified instanceof Problem problem) {
                recordErrors++;
                errors.merge(problem.code(), 1L, Long::sum);
            } else if (classified instanceof NoStatus none) {
                filtered.merge(none.reason(), 1L, Long::sum);
            } else if (classified instanceof Eligible eligible) {
                if (key(eligible.date(), fileIndex, record.position()) != index.get(eligible.serial())) {
                    superseded++;
                } else {
                    latest(eligible, record);
                }
            }
        }

        /** The record is the latest of its serial number: the filters decide. */
        private void latest(Eligible eligible, UsptoXmlReader.RawRecord record) throws IOException {
            if (!filters.isLive(eligible.statusCode())) {
                deadRemoved++;
                deadByStatus.merge(eligible.statusCode(), 1L, Long::sum);
                return;
            }
            if (filters.textOnly()) {
                String drawing = record.drawing() == null ? "" : record.drawing().trim();
                if (drawing.isEmpty()) {
                    drop(MISSING_DRAWING_CODE);
                    return;
                }
                switch (drawing.charAt(0)) {
                    case '1', '3', '4', '5' -> {
                        // lettering, with or without a design: the text of the mark is read
                    }
                    case '2' -> {
                        drop(LOGO_ONLY);
                        return;
                    }
                    case '6' -> {
                        drop(NO_DRAWING);
                        return;
                    }
                    default -> {
                        drop(UNKNOWN_DRAWING_CODE);
                        return;
                    }
                }
            }
            String display = record.mark() == null ? "" : record.mark().strip();
            if (display.isEmpty()) {
                drop(BLANK_MARK);
                return;
            }
            if (display.length() > CatalogImport.MAX_MARK_CHARS) {
                drop(MARK_TOO_LONG);
                return;
            }
            if (record.classes().isEmpty()) {
                drop(NO_VALID_NICE_CLASS);
                return;
            }
            if (!filters.includesAnyClass(record.classes())) {
                drop(CLASS_NOT_INCLUDED);
                return;
            }
            String normalized = normalizer.normalizeMark(display);
            if (normalized.isEmpty()) {
                drop(MARK_EMPTY_AFTER_NORMALIZATION);
                return;
            }
            if (normalized.length() > CatalogImport.MAX_MARK_CHARS) {
                drop(MARK_TOO_LONG_AFTER_NORMALIZATION);
                return;
            }
            ObjectNode json = JSON.createObjectNode();
            json.put("mark", display);
            json.put("status", eligible.status().name());
            ArrayNode niceClasses = json.putArray("nice_classes");
            record.classes().forEach(niceClasses::add);
            json.put("source_id", eligible.serialText());
            writer.write(JSON.writeValueAsString(json));
            writer.write('\n');
            recordsWritten++;
            ignoredClassCodes += record.ignoredClassCodes();
        }

        private void drop(String reason) {
            filtered.merge(reason, 1L, Long::sum);
        }

        ConversionReport report(List<FileState> files, int distinctSerials, long outputBytes, String outputSha256) {
            List<ConversionReport.FileReport> reports = new ArrayList<>();
            List<ConversionReport.RecordError> details = new ArrayList<>();
            boolean otherVersion = false;
            long failed = 0;
            for (FileState file : files) {
                if (file.failure != null) {
                    failed++;
                    reports.add(new ConversionReport.FileReport(file.name, 0, null, null, null, false, 0,
                            ConversionReport.FileStatus.FAILED, file.failure));
                    continue;
                }
                UsptoXmlReader.FileScan scan = file.scan;
                otherVersion |= !EXPECTED_VERSION_NO.equals(scan.versionNo());
                reports.add(new ConversionReport.FileReport(file.name, scan.bytes(), scan.sha256(), scan.versionNo(),
                        scan.versionDate(), scan.dataAvailable(), scan.records(), ConversionReport.FileStatus.OK, null));
                for (ConversionReport.RecordError error : file.details) {
                    if (details.size() < ConversionReport.MAX_ERROR_DETAILS) {
                        details.add(error);
                    }
                }
            }
            Completeness completeness = failed == 0 && recordErrors == 0 ? Completeness.COMPLETE
                    : Completeness.INCOMPLETE;
            return new ConversionReport(completeness, options.strict(), statusMap.version(), statusMap.contentHash(),
                    filters.version(), filters.contentHash(), otherVersion, reports, recordsRead, recordsWritten,
                    recordErrors, superseded, deadRemoved, distinctSerials, ignoredClassCodes, filtered, errors,
                    deadByStatus, details, outputBytes, outputSha256);
        }
    }

    private static final class CountingOutput extends FilterOutputStream {
        private long count;

        CountingOutput(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
            count += length;
        }
    }
}
