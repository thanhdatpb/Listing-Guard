package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedWriter;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Converts a USPTO Trademark Applications Daily XML file (TDXF, DTD V2.0) to {@code jsonl-1} (ADR-009, PR A2b). It
 * reads the XML with StAX as a stream and writes each record as soon as its {@code case-file} ends, so its memory does
 * not depend on the size of the file. It is <b>unverified against real data</b>: it follows the public V2.0 DTD and
 * element documentation, which ADR-009 lists; no real file has been read.
 *
 * <p>The elements read, relative to a {@code case-file} under {@code application-information/file-segments/
 * action-keys}: {@code serial-number} (the {@code source_id}), {@code case-file-header/mark-identification} (the
 * mark), {@code case-file-header/mark-drawing-code}, {@code case-file-header/status-code} and
 * {@code classifications/classification/international-code}. The status of a single class is not read.
 *
 * <p>A record is dropped, and counted under the first reason that applies, in this order: {@code missing_serial} or
 * {@code invalid_serial} (not eight digits), the drawing code ({@code missing_drawing_code}, {@code logo_only} for
 * first character 2, {@code no_drawing} for 6, {@code unknown_drawing_code}; 1, 3, 4 and 5 are kept), {@code blank_mark},
 * {@code mark_too_long}, the status ({@code status_dropped:<code>} or {@code status_unmapped:<code>}), {@code
 * no_valid_nice_class}, {@code mark_empty_after_normalization} and {@code mark_too_long_after_normalization} (the
 * import refuses a whole catalog for either, so they are dropped here). The same serial number may be written more
 * than once: there is no "latest transaction wins" yet (ADR-009, PR A2c).
 *
 * <p>The file must declare {@code version/version-no} {@value #EXPECTED_VERSION_NO}, or the conversion stops, unless
 * {@code allowOtherVersion} is true. The DTD is never loaded and no external entity is resolved, so reading a file never
 * touches the network. The messages and the report hold no mark.
 */
public final class UsptoDailyConverter {

    public static final String EXPECTED_VERSION_NO = "2.0";
    public static final String ROOT_ELEMENT = "trademark-applications-daily";

    public static final String MISSING_SERIAL = "missing_serial";
    public static final String INVALID_SERIAL = "invalid_serial";
    public static final String MISSING_DRAWING_CODE = "missing_drawing_code";
    public static final String LOGO_ONLY = "logo_only";
    public static final String NO_DRAWING = "no_drawing";
    public static final String UNKNOWN_DRAWING_CODE = "unknown_drawing_code";
    public static final String BLANK_MARK = "blank_mark";
    public static final String MARK_TOO_LONG = "mark_too_long";
    public static final String NO_VALID_NICE_CLASS = "no_valid_nice_class";
    public static final String MARK_EMPTY_AFTER_NORMALIZATION = "mark_empty_after_normalization";
    public static final String MARK_TOO_LONG_AFTER_NORMALIZATION = "mark_too_long_after_normalization";
    public static final String STATUS_DROPPED = "status_dropped:";
    public static final String STATUS_UNMAPPED = "status_unmapped:";

    /** The deepest element the converter follows; the DTD is far shallower. */
    private static final int MAX_DEPTH = 64;
    /** The most text kept of one element. A value this long is wrong for every element read, and a mark over 300 is dropped. */
    private static final int MAX_CAPTURE_CHARS = 4_000;
    private static final int BUFFER_CHARS = 64 * 1024;
    private static final Pattern SERIAL = Pattern.compile("\\d{8}");
    private static final Pattern STATUS_CODE = Pattern.compile("\\d{3}");
    private static final Pattern CLASS_CODE = Pattern.compile("\\d{1,4}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final UsptoStatusMap statusMap;
    private final TextNormalizer normalizer;
    private final boolean allowOtherVersion;

    /**
     * @param normalizer        the normalizer of the active trademark ruleset: the import normalizes each mark with it
     *                          and refuses a catalog for a mark that becomes empty or too long
     * @param allowOtherVersion read a file that declares a {@code version-no} other than 2.0
     */
    public UsptoDailyConverter(UsptoStatusMap statusMap, TextNormalizer normalizer, boolean allowOtherVersion) {
        this.statusMap = statusMap;
        this.normalizer = normalizer;
        this.allowOtherVersion = allowOtherVersion;
    }

    /**
     * Reads the XML from {@code xml} and writes {@code jsonl-1} (UTF-8, LF) to {@code jsonl}. Neither stream is closed;
     * the output is flushed. On an exception the output holds a partial result and must be discarded.
     *
     * @throws UsptoConversionException if the XML is not well-formed, is not a Trademark Applications Daily file or
     *                                  declares an unsupported version
     * @throws IOException              if a stream fails
     */
    public ConversionReport convert(InputStream xml, OutputStream jsonl) throws IOException {
        MessageDigest inDigest = newDigest();
        MessageDigest outDigest = newDigest();
        CountingInput in = new CountingInput(new DigestInputStream(xml, inDigest));
        CountingOutput out = new CountingOutput(new DigestOutputStream(jsonl, outDigest));
        Run run = new Run(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), BUFFER_CHARS));
        XMLStreamReader reader = null;
        try {
            reader = factory().createXMLStreamReader(in);
            run.read(reader);
        } catch (XMLStreamException e) {
            throw new UsptoConversionException("The XML is not well-formed" + where(e) + ".", e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // nothing to release that matters
                }
            }
        }
        run.writer.flush();
        in.transferTo(OutputStream.nullOutputStream()); // the digest covers the whole input, even what StAX left
        return run.report(in.count, hex(inDigest), out.count, hex(outDigest));
    }

    private static String where(XMLStreamException e) {
        return e.getLocation() == null ? ""
                : " (line " + e.getLocation().getLineNumber() + ", column " + e.getLocation().getColumnNumber() + ")";
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        // not coalescing: a text node is read in chunks, and the converter keeps only a bounded part of it
        factory.setProperty(XMLInputFactory.IS_COALESCING, false);
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
            // another StAX implementation without the JDK property; the two above already forbid the DTD
        }
        return factory;
    }

    private enum Field {
        VERSION_NO, VERSION_DATE, DATA_AVAILABLE, SERIAL, MARK, DRAWING, STATUS, CLASS_CODE
    }

    /** The state of one conversion. */
    private final class Run {
        private final Writer writer;
        private final List<String> stack = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final Map<String, Long> dropped = new TreeMap<>();
        private final TreeSet<Integer> classes = new TreeSet<>();

        private Field capture;
        private int captureDepth;
        private boolean inCaseFile;
        private boolean versionChecked;
        private String versionNo;
        private String versionDate;
        private String dataAvailableCode;
        private String serial;
        private String mark;
        private String drawing;
        private String status;
        private int ignoredClassesOfRecord;
        private long recordsRead;
        private long recordsWritten;
        private long ignoredClassCodes;

        Run(Writer writer) {
            this.writer = writer;
        }

        void read(XMLStreamReader reader) throws XMLStreamException, IOException {
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> start(reader.getLocalName());
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                            characters(reader.getText());
                    case XMLStreamConstants.END_ELEMENT -> end();
                    default -> {
                        // comments, processing instructions and the document markers carry nothing we read
                    }
                }
            }
            if (!versionChecked) {
                throw new UsptoConversionException("The file has no version element, so it is not a Trademark "
                        + "Applications Daily file this converter can read.");
            }
        }

        private void start(String name) {
            if (stack.size() >= MAX_DEPTH) {
                throw new UsptoConversionException("The XML is nested more than " + MAX_DEPTH + " elements deep.");
            }
            if (stack.isEmpty() && !ROOT_ELEMENT.equals(name)) {
                throw new UsptoConversionException("This is not a Trademark Applications Daily file: its root element is <"
                        + abbreviate(name) + ">, expected <" + ROOT_ELEMENT + ">.");
            }
            stack.add(name);
            int depth = stack.size();
            if (capture != null) {
                return; // an element inside one that is being read: not part of the value
            }
            if (depth == 2 && "application-information".equals(name) && !versionChecked) {
                throw new UsptoConversionException("The file has no version element before its data.");
            }
            if (depth == 5 && "case-file".equals(name) && "action-keys".equals(stack.get(3))) {
                inCaseFile = true;
                recordsRead++;
                serial = null;
                mark = null;
                drawing = null;
                status = null;
                classes.clear();
                ignoredClassesOfRecord = 0;
                return;
            }
            Field field = fieldOf(name, depth);
            if (field != null) {
                capture = field;
                captureDepth = depth;
                text.setLength(0);
            }
        }

        private Field fieldOf(String name, int depth) {
            if (depth == 3 && "version".equals(stack.get(1))) {
                return switch (name) {
                    case "version-no" -> Field.VERSION_NO;
                    case "version-date" -> Field.VERSION_DATE;
                    default -> null;
                };
            }
            if (depth == 3 && "application-information".equals(stack.get(1)) && "data-available-code".equals(name)) {
                return Field.DATA_AVAILABLE;
            }
            if (!inCaseFile) {
                return null;
            }
            if (depth == 6 && "serial-number".equals(name)) {
                return Field.SERIAL;
            }
            if (depth == 7 && "case-file-header".equals(stack.get(5))) {
                return switch (name) {
                    case "mark-identification" -> Field.MARK;
                    case "mark-drawing-code" -> Field.DRAWING;
                    case "status-code" -> Field.STATUS;
                    default -> null;
                };
            }
            if (depth == 8 && "international-code".equals(name) && "classifications".equals(stack.get(5))
                    && "classification".equals(stack.get(6))) {
                return Field.CLASS_CODE;
            }
            return null;
        }

        private void characters(String chunk) {
            if (capture != null && stack.size() == captureDepth && text.length() < MAX_CAPTURE_CHARS) {
                text.append(chunk, 0, Math.min(chunk.length(), MAX_CAPTURE_CHARS - text.length()));
            }
        }

        private void end() throws IOException {
            int depth = stack.size();
            if (capture != null && depth == captureDepth) {
                finishField();
                capture = null;
            }
            if (depth == 5 && inCaseFile) {
                finishCaseFile();
                inCaseFile = false;
            }
            if (depth == 2 && "version".equals(stack.get(1))) {
                checkVersion();
            }
            stack.remove(depth - 1);
        }

        private void finishField() {
            String value = text.toString();
            switch (capture) {
                case VERSION_NO -> versionNo = value.trim();
                case VERSION_DATE -> versionDate = value.trim();
                case DATA_AVAILABLE -> dataAvailableCode = value.trim();
                case SERIAL -> serial = value;
                case MARK -> mark = value;
                case DRAWING -> drawing = value;
                case STATUS -> status = value;
                case CLASS_CODE -> {
                    String code = value.trim();
                    int niceClass = CLASS_CODE.matcher(code).matches() ? Integer.parseInt(code) : 0;
                    if (niceClass >= 1 && niceClass <= 45) {
                        classes.add(niceClass);
                    } else {
                        ignoredClassesOfRecord++;
                    }
                }
            }
        }

        private void checkVersion() {
            if (versionNo == null || versionNo.isEmpty()) {
                throw new UsptoConversionException("The file's version element has no version-no.");
            }
            if (!EXPECTED_VERSION_NO.equals(versionNo) && !allowOtherVersion) {
                throw new UsptoConversionException("The file declares version-no '" + abbreviate(versionNo)
                        + "' and version-date '" + abbreviate(versionDate == null ? "" : versionDate)
                        + "'; this converter reads only version " + EXPECTED_VERSION_NO + " of the Trademark "
                        + "Applications Daily DTD. Set listing-guard.trademark.uspto.allow-other-version=true to "
                        + "read it anyway; the output is then unchecked against that version's DTD.");
            }
            versionChecked = true;
        }

        private void finishCaseFile() throws IOException {
            ObjectNode record = evaluate();
            if (record == null) {
                return;
            }
            writer.write(JSON.writeValueAsString(record));
            writer.write('\n');
            recordsWritten++;
            ignoredClassCodes += ignoredClassesOfRecord;
        }

        /** The record to write, or null after counting the reason it is dropped. */
        private ObjectNode evaluate() {
            String serialText = serial == null ? "" : serial.trim();
            if (serialText.isEmpty()) {
                return drop(MISSING_SERIAL);
            }
            if (!SERIAL.matcher(serialText).matches()) {
                return drop(INVALID_SERIAL);
            }
            String drawingText = drawing == null ? "" : drawing.trim();
            if (drawingText.isEmpty()) {
                return drop(MISSING_DRAWING_CODE);
            }
            switch (drawingText.charAt(0)) {
                case '1', '3', '4', '5' -> {
                    // lettering, with or without a design: the text of the mark is read
                }
                case '2' -> {
                    return drop(LOGO_ONLY);
                }
                case '6' -> {
                    return drop(NO_DRAWING);
                }
                default -> {
                    return drop(UNKNOWN_DRAWING_CODE);
                }
            }
            String display = mark == null ? "" : mark.strip();
            if (display.isEmpty()) {
                return drop(BLANK_MARK);
            }
            if (display.length() > CatalogImport.MAX_MARK_CHARS) {
                return drop(MARK_TOO_LONG);
            }
            String code = status == null ? "" : status.trim();
            String codeKey = code.isEmpty() ? "missing" : STATUS_CODE.matcher(code).matches() ? code : "invalid";
            UsptoStatusMap.Outcome outcome = "missing".equals(codeKey) || "invalid".equals(codeKey)
                    ? new UsptoStatusMap.Unmapped() : statusMap.outcome(code);
            if (outcome instanceof UsptoStatusMap.Dropped) {
                return drop(STATUS_DROPPED + codeKey);
            }
            if (!(outcome instanceof UsptoStatusMap.Mapped mapped)) {
                return drop(STATUS_UNMAPPED + codeKey);
            }
            if (classes.isEmpty()) {
                return drop(NO_VALID_NICE_CLASS);
            }
            String normalized = normalizer.normalizeMark(display);
            if (normalized.isEmpty()) {
                return drop(MARK_EMPTY_AFTER_NORMALIZATION);
            }
            if (normalized.length() > CatalogImport.MAX_MARK_CHARS) {
                return drop(MARK_TOO_LONG_AFTER_NORMALIZATION);
            }
            ObjectNode record = JSON.createObjectNode();
            record.put("mark", display);
            record.put("status", mapped.status().name());
            ArrayNode niceClasses = record.putArray("nice_classes");
            classes.forEach(niceClasses::add);
            record.put("source_id", serialText);
            return record;
        }

        private ObjectNode drop(String reason) {
            dropped.merge(reason, 1L, Long::sum);
            return null;
        }

        ConversionReport report(long inputBytes, String inputSha256, long outputBytes, String outputSha256) {
            return new ConversionReport(statusMap.version(), statusMap.contentHash(), versionNo, versionDate,
                    !EXPECTED_VERSION_NO.equals(versionNo), !"N".equals(dataAvailableCode), recordsRead,
                    recordsWritten, ignoredClassCodes, dropped, inputBytes, inputSha256, outputBytes, outputSha256);
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 40 ? value : value.substring(0, 40) + "...";
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class CountingInput extends FilterInputStream {
        private long count;

        CountingInput(InputStream in) {
            super(in);
        }

        /** The JDK parser closes the stream it reads; the caller owns the stream, and the digest needs the rest of it. */
        @Override
        public void close() {
            // left open on purpose
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count += n;
            }
            return n;
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
