package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads one USPTO Trademark Applications Daily XML file (TDXF, DTD V2.0) with StAX as a stream and hands each
 * {@code case-file} to a handler as soon as it ends, so its memory does not depend on the size of the file (ADR-009,
 * PRs A2b and A2c). It reads the values and decides nothing: what a record means is the converter's work. It is
 * <b>unverified against real data</b>: it follows the public V2.0 DTD and element documentation that ADR-009 lists.
 *
 * <p>The elements read, relative to a {@code case-file} under {@code application-information/file-segments/
 * action-keys}: {@code serial-number}, {@code transaction-date} (both children of the case file),
 * {@code case-file-header/mark-identification}, {@code case-file-header/mark-drawing-code},
 * {@code case-file-header/status-code} and {@code classifications/classification/international-code}. The status of a
 * single class is not read.
 *
 * <p>The file must declare {@code version/version-no} {@value #EXPECTED_VERSION_NO}, or reading stops, unless
 * {@code allowOtherVersion} is true. The DTD is never loaded and no external entity is resolved, so reading a file never
 * touches the network. Messages hold no mark.
 */
final class UsptoXmlReader {

    static final String EXPECTED_VERSION_NO = "2.0";
    static final String ROOT_ELEMENT = "trademark-applications-daily";

    /** The deepest element followed; the DTD is far shallower. */
    private static final int MAX_DEPTH = 64;
    /** The most text kept of one element. A value this long is wrong for every element read, and a mark over 300 is dropped. */
    private static final int MAX_CAPTURE_CHARS = 4_000;
    private static final java.util.regex.Pattern CLASS_CODE = java.util.regex.Pattern.compile("\\d{1,4}");

    /**
     * One {@code case-file}: the raw text of the values (not trimmed, null when the element is absent).
     *
     * @param position the 1-based place of the case file among the case files of the file
     * @param line     the line of its start tag, 0 when the parser gave none
     * @param column   the column of its start tag, 0 when the parser gave none
     * @param classes  the international codes that are Nice classes 1 to 45, sorted
     * @param ignoredClassCodes the international codes that are not (for example {@code A}, {@code B}, {@code 200})
     */
    record RawRecord(long position, long line, long column, String serial, String transactionDate, String mark,
                     String drawing, String status, SortedSet<Integer> classes, int ignoredClassCodes) {
    }

    @FunctionalInterface
    interface Handler {
        void record(RawRecord record) throws IOException;
    }

    /** What was found in the file besides the records. */
    record FileScan(String versionNo, String versionDate, boolean dataAvailable, long records, long bytes,
                    String sha256) {
    }

    private UsptoXmlReader() {
    }

    /**
     * Reads {@code xml} to its end. The stream is not closed; it is read to its end so the digest covers every byte.
     *
     * @throws UsptoConversionException if the XML is not well-formed, is not a Trademark Applications Daily file or
     *                                  declares an unsupported version
     */
    static FileScan read(InputStream xml, boolean allowOtherVersion, Handler handler) throws IOException {
        MessageDigest digest = newDigest();
        CountingInput in = new CountingInput(new DigestInputStream(xml, digest));
        Run run = new Run(allowOtherVersion, handler);
        XMLStreamReader reader = null;
        try {
            reader = factory().createXMLStreamReader(in);
            run.read(reader);
        } catch (XMLStreamException e) {
            throw new UsptoConversionException("The XML is not well-formed" + where(e.getLocation()) + ".", e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // nothing to release that matters
                }
            }
        }
        in.transferTo(OutputStream.nullOutputStream()); // the digest covers the whole input, even what StAX left
        return new FileScan(run.versionNo, run.versionDate, !"N".equals(run.dataAvailableCode), run.records, in.count,
                HexFormat.of().formatHex(digest.digest()));
    }

    private static String where(Location location) {
        return location == null ? ""
                : " (line " + location.getLineNumber() + ", column " + location.getColumnNumber() + ")";
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        // not coalescing: a text node is read in chunks, and only a bounded part of it is kept
        factory.setProperty(XMLInputFactory.IS_COALESCING, false);
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
            // another StAX implementation without the JDK property; the two above already forbid the DTD
        }
        return factory;
    }

    private enum Field {
        VERSION_NO, VERSION_DATE, DATA_AVAILABLE, SERIAL, TRANSACTION_DATE, MARK, DRAWING, STATUS, CLASS_CODE
    }

    /** The state of one read. */
    private static final class Run {
        private final boolean allowOtherVersion;
        private final Handler handler;
        private final List<String> stack = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final SortedSet<Integer> classes = new TreeSet<>();

        private Field capture;
        private int captureDepth;
        private boolean inCaseFile;
        private boolean versionChecked;
        private String versionNo;
        private String versionDate;
        private String dataAvailableCode;
        private String serial;
        private String transactionDate;
        private String mark;
        private String drawing;
        private String status;
        private int ignoredClassesOfRecord;
        private long records;
        private long line;
        private long column;

        Run(boolean allowOtherVersion, Handler handler) {
            this.allowOtherVersion = allowOtherVersion;
            this.handler = handler;
        }

        void read(XMLStreamReader reader) throws XMLStreamException, IOException {
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> start(reader.getLocalName(), reader.getLocation());
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

        private void start(String name, Location location) {
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
                records++;
                line = location == null ? 0 : location.getLineNumber();
                column = location == null ? 0 : location.getColumnNumber();
                serial = null;
                transactionDate = null;
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
            if (depth == 6) {
                return switch (name) {
                    case "serial-number" -> Field.SERIAL;
                    case "transaction-date" -> Field.TRANSACTION_DATE;
                    default -> null;
                };
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
                handler.record(new RawRecord(records, line, column, serial, transactionDate, mark, drawing, status,
                        new TreeSet<>(classes), ignoredClassesOfRecord));
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
                case TRANSACTION_DATE -> transactionDate = value;
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
}
