package com.thanhdatpb.listingguard.api.batch;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the input file one row at a time, so a file never has to fit in memory. The first row is the header. A UTF-8
 * (or UTF-16) byte order mark is dropped, quoted values may hold the delimiter and line breaks, and wholly empty lines
 * are skipped. Bytes that are not valid in the declared encoding stop the read instead of becoming a replacement
 * character: a listing text silently damaged by a wrong encoding could hide the very mark the batch looks for.
 *
 * <p>Every row carries the line of the file where it starts (the header of a file with no skipped lines is line 1), so a
 * report can point at it even when an earlier row has quoted line breaks.
 */
final class BatchCsvReader implements AutoCloseable {

    /** One data row: where it starts in the file and its cells, as written. */
    record Row(int line, String[] cells) {
    }

    /** The file itself is damaged (bad bytes, an unclosed quote): no row after this point can be trusted. */
    static final class FormatException extends RuntimeException {
        FormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final CsvMapper MAPPER = new CsvMapper()
            .enable(CsvParser.Feature.WRAP_AS_ARRAY)
            .enable(CsvParser.Feature.SKIP_EMPTY_LINES);

    private final MappingIterator<String[]> rows;
    private final BatchMapping.Csv settings;
    private final int skippedLines;
    private final List<String> header;
    private final int headerLine;
    private int lastLine;

    private BatchCsvReader(MappingIterator<String[]> rows, BatchMapping.Csv settings, List<String> header, int headerLine) {
        this.rows = rows;
        this.settings = settings;
        this.skippedLines = settings.skipLines();
        this.header = header;
        this.headerLine = headerLine;
        this.lastLine = headerLine;
    }

    /**
     * Opens the file and reads its header row.
     *
     * @throws BatchConfigException if the file cannot be read, has no header row, or is damaged before its first row ends
     */
    static BatchCsvReader open(Path file, BatchMapping.Csv settings) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file),
                    settings.encoding().newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)));
            dropByteOrderMark(reader);
            for (int i = 0; i < settings.skipLines(); i++) {
                if (reader.readLine() == null) {
                    break;
                }
            }
            CsvSchema schema = CsvSchema.emptySchema().withColumnSeparator(settings.delimiter());
            MappingIterator<String[]> rows = MAPPER.readerFor(String[].class).with(schema).readValues(reader);
            if (!rows.hasNext()) {
                rows.close();
                throw new BatchConfigException("The input file " + file + " has no header row"
                        + (settings.skipLines() > 0 ? " after the " + settings.skipLines() + " lines skipped (csv.skip_lines)" : "")
                        + ".");
            }
            int headerLine = rows.getCurrentLocation().getLineNr() + settings.skipLines();
            List<String> header = new ArrayList<>();
            for (String name : rows.next()) {
                header.add(name.trim());
            }
            return new BatchCsvReader(rows, settings, List.copyOf(header), headerLine);
        } catch (IOException e) {
            closeQuietly(reader);
            if (isCodingError(e)) {
                // decoding runs in chunks, so a small file fails here, on its first read, and a large one later in next()
                throw new BatchConfigException("The input file " + file + " " + codingMessage(settings, 1), e);
            }
            throw new BatchConfigException("Cannot read the input file " + file + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            closeQuietly(reader);
            if (e instanceof BatchConfigException) {
                throw e;
            }
            throw new BatchConfigException("The header of the input file " + file + " cannot be read: "
                    + describe(e, settings, 0), e);
        }
    }

    /** The header names, trimmed. */
    List<String> header() {
        return header;
    }

    int headerLine() {
        return headerLine;
    }

    /**
     * The next data row, or null at the end of the file.
     *
     * @throws FormatException if the file is damaged from here on
     */
    Row next() {
        try {
            if (!rows.hasNext()) {
                return null;
            }
            // after hasNext() the parser stands at the start of the row that next() returns
            int line = rows.getCurrentLocation().getLineNr() + skippedLines;
            String[] cells = rows.next();
            lastLine = line;
            return new Row(line, cells);
        } catch (RuntimeException e) {
            throw new FormatException(describe(e, settings, lastLine), e);
        }
    }

    /** True when at least one more row follows. */
    boolean hasNext() {
        try {
            return rows.hasNext();
        } catch (RuntimeException e) {
            throw new FormatException(describe(e, settings, lastLine), e);
        }
    }

    @Override
    public void close() {
        try {
            rows.close();
        } catch (IOException e) {
            // nothing to recover: the file was only read
        }
    }

    private static void dropByteOrderMark(BufferedReader reader) throws IOException {
        reader.mark(1);
        if (reader.read() != 0xFEFF) {
            reader.reset();
        }
    }

    private static void closeQuietly(Reader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                // the original failure is the one to report
            }
        }
    }

    private static boolean isCodingError(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof CharacterCodingException) {
                return true;
            }
        }
        return false;
    }

    private static String codingMessage(BatchMapping.Csv settings, int lastLine) {
        return "has bytes that are not valid " + settings.encoding().name() + " (at or after line " + lastLine
                + "). Set csv.encoding in the mapping to the encoding the file was saved in.";
    }

    private static String describe(RuntimeException e, BatchMapping.Csv settings, int lastLine) {
        if (isCodingError(e)) {
            return "the file " + codingMessage(settings, Math.max(lastLine, 1));
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int end = message.indexOf('\n');
        return "the file is not well-formed CSV after line " + lastLine + " ("
                + (end < 0 ? message : message.substring(0, end)) + ")";
    }
}
