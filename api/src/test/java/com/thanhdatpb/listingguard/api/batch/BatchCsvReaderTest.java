package com.thanhdatpb.listingguard.api.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading the input file as a stream: header, byte order mark, delimiters, encodings, line numbers, damaged files. */
class BatchCsvReaderTest {

    @TempDir
    Path dir;

    private Path write(String content, Charset charset) throws IOException {
        return Files.write(dir.resolve("input.csv"), content.getBytes(charset));
    }

    private static List<BatchCsvReader.Row> all(BatchCsvReader reader) {
        List<BatchCsvReader.Row> rows = new ArrayList<>();
        for (BatchCsvReader.Row row = reader.next(); row != null; row = reader.next()) {
            rows.add(row);
        }
        return rows;
    }

    @Test
    void readsTheHeaderAndTheRowsAndDropsAUtf8ByteOrderMark() throws IOException {
        Path file = Files.write(dir.resolve("bom.csv"), concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "id,title\n1,Cốc sứ\n2,Mug\n".getBytes(StandardCharsets.UTF_8)));

        try (BatchCsvReader reader = BatchCsvReader.open(file, BatchMapping.Csv.DEFAULT)) {
            assertThat(reader.header()).containsExactly("id", "title");
            assertThat(reader.headerLine()).isEqualTo(1);
            List<BatchCsvReader.Row> rows = all(reader);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).cells()).containsExactly("1", "Cốc sứ");
            assertThat(rows.get(1).cells()).containsExactly("2", "Mug");
        }
    }

    @Test
    void headerNamesAreTrimmedButCellsAreKeptAsWritten() throws IOException {
        Path file = write("id , title\n1, padded \n", StandardCharsets.UTF_8);

        try (BatchCsvReader reader = BatchCsvReader.open(file, BatchMapping.Csv.DEFAULT)) {
            assertThat(reader.header()).containsExactly("id", "title");
            assertThat(reader.next().cells()).containsExactly("1", " padded ");
        }
    }

    @Test
    void aRowReportsTheLineWhereItStartsEvenAfterQuotedLineBreaksAndBlankLines() throws IOException {
        Path file = write("id,title,tags\n"            // 1
                + "1,Hello,\"a,b\"\n"                   // 2
                + "\n"                                  // 3 (blank)
                + "2,\"multi\nline\",x\n"               // 4-5
                + "3,short\r\n"                         // 6
                + "4,\"say \"\"hi\"\"\",y\n"            // 7
                + "5,last,end", StandardCharsets.UTF_8); // 8, no final line break

        try (BatchCsvReader reader = BatchCsvReader.open(file, BatchMapping.Csv.DEFAULT)) {
            List<BatchCsvReader.Row> rows = all(reader);
            assertThat(rows).extracting(BatchCsvReader.Row::line).containsExactly(2, 4, 6, 7, 8);
            assertThat(rows.get(0).cells()).containsExactly("1", "Hello", "a,b");
            assertThat(rows.get(1).cells()).containsExactly("2", "multi\nline", "x");
            assertThat(rows.get(2).cells()).containsExactly("3", "short");
            assertThat(rows.get(3).cells()).containsExactly("4", "say \"hi\"", "y");
        }
    }

    @Test
    void theDelimiterIsConfigurable() throws IOException {
        Path semicolon = Files.writeString(dir.resolve("semicolon.csv"), "id;title\n1;a,b\n", StandardCharsets.UTF_8);
        Path tab = Files.writeString(dir.resolve("tab.csv"), "id\ttitle\n1\ta;b\n", StandardCharsets.UTF_8);

        try (BatchCsvReader reader = BatchCsvReader.open(semicolon, new BatchMapping.Csv(';', StandardCharsets.UTF_8, 0))) {
            assertThat(reader.header()).containsExactly("id", "title");
            assertThat(reader.next().cells()).containsExactly("1", "a,b");
        }
        try (BatchCsvReader reader = BatchCsvReader.open(tab, new BatchMapping.Csv('\t', StandardCharsets.UTF_8, 0))) {
            assertThat(reader.next().cells()).containsExactly("1", "a;b");
        }
    }

    @Test
    void theEncodingIsConfigurable() throws IOException {
        Charset latin = Charset.forName("windows-1252");
        Path file = write("id,title\n1,Café\n", latin);

        try (BatchCsvReader reader = BatchCsvReader.open(file, new BatchMapping.Csv(',', latin, 0))) {
            assertThat(reader.next().cells()).containsExactly("1", "Café");
        }
    }

    @Test
    void utf16WithItsOwnByteOrderMarkIsReadToo() throws IOException {
        Path file = write("﻿id,title\n1,Cốc\n", StandardCharsets.UTF_16LE);

        try (BatchCsvReader reader = BatchCsvReader.open(file, new BatchMapping.Csv(',', StandardCharsets.UTF_16LE, 0))) {
            assertThat(reader.header()).containsExactly("id", "title");
            assertThat(reader.next().cells()).containsExactly("1", "Cốc");
        }
    }

    @Test
    void linesBeforeTheHeaderCanBeSkippedAndTheLineNumbersStayThoseOfTheFile() throws IOException {
        Path file = write("TemplateType=made-up\nlabels for people\nid,title\n1,a\n2,b\n", StandardCharsets.UTF_8);

        try (BatchCsvReader reader = BatchCsvReader.open(file, new BatchMapping.Csv(',', StandardCharsets.UTF_8, 2))) {
            assertThat(reader.header()).containsExactly("id", "title");
            assertThat(reader.headerLine()).isEqualTo(3);
            assertThat(all(reader)).extracting(BatchCsvReader.Row::line).containsExactly(4, 5);
        }
    }

    @Test
    void aRowKeepsTheNumberOfCellsItHasSoTheMappingCanRefuseIt() throws IOException {
        Path file = write("a,b,c\n1,2\n1,2,3,4\n", StandardCharsets.UTF_8);

        try (BatchCsvReader reader = BatchCsvReader.open(file, BatchMapping.Csv.DEFAULT)) {
            List<BatchCsvReader.Row> rows = all(reader);
            assertThat(rows.get(0).cells()).hasSize(2);
            assertThat(rows.get(1).cells()).hasSize(4);
        }
    }

    @Test
    void hasNextSaysWhetherAnotherRowFollowsWithoutConsumingIt() throws IOException {
        Path file = write("id\n1\n2\n", StandardCharsets.UTF_8);

        try (BatchCsvReader reader = BatchCsvReader.open(file, BatchMapping.Csv.DEFAULT)) {
            assertThat(reader.hasNext()).isTrue();
            assertThat(reader.next().cells()).containsExactly("1");
            assertThat(reader.hasNext()).isTrue();
            assertThat(reader.next().cells()).containsExactly("2");
            assertThat(reader.hasNext()).isFalse();
            assertThat(reader.next()).isNull();
        }
    }

    @Test
    void anEmptyFileOrOneWithoutDataIsHandled() throws IOException {
        Path empty = write("", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> BatchCsvReader.open(empty, BatchMapping.Csv.DEFAULT))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("no header row");

        Path onlyHeader = write("id,title\n", StandardCharsets.UTF_8);
        try (BatchCsvReader reader = BatchCsvReader.open(onlyHeader, BatchMapping.Csv.DEFAULT)) {
            assertThat(reader.header()).containsExactly("id", "title");
            assertThat(reader.next()).isNull();
        }

        Path skippedAll = write("one\ntwo\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> BatchCsvReader.open(skippedAll, new BatchMapping.Csv(',', StandardCharsets.UTF_8, 2)))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("csv.skip_lines");
    }

    @Test
    void aMissingFileIsRefusedWithItsName() {
        Path missing = dir.resolve("missing.csv");

        assertThatThrownBy(() -> BatchCsvReader.open(missing, BatchMapping.Csv.DEFAULT))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("missing.csv");
    }

    @Test
    void bytesThatAreNotValidInTheEncodingAreRefusedInsteadOfBecomingReplacementCharacters() throws IOException {
        // "Café" saved as windows-1252 (the é is the single byte 0xE9) but read as UTF-8. The decoder works in chunks, so
        // a small file like this one fails at open.
        Path file = write("id,title\n1,fine\n2,Café\n3,after\n", Charset.forName("windows-1252"));

        assertThatThrownBy(() -> BatchCsvReader.open(file, BatchMapping.Csv.DEFAULT))
                .isInstanceOf(BatchConfigException.class)
                .hasMessageContaining("not valid UTF-8").hasMessageContaining("csv.encoding");
    }

    @Test
    void badBytesFarIntoALargeFileStopTheReadAfterTheRowsBeforeThem() throws IOException {
        StringBuilder text = new StringBuilder("id,title\n");
        for (int i = 1; i <= 5000; i++) {
            text.append(i).append(",fine row\n");
        }
        text.append("5001,Café\n5002,after\n");
        Path file = write(text.toString(), Charset.forName("windows-1252"));

        try (BatchCsvReader reader = BatchCsvReader.open(file, BatchMapping.Csv.DEFAULT)) {
            List<BatchCsvReader.Row> rows = new ArrayList<>();
            assertThatThrownBy(() -> {
                for (BatchCsvReader.Row row = reader.next(); row != null; row = reader.next()) {
                    rows.add(row);
                }
            }).isInstanceOf(BatchCsvReader.FormatException.class)
                    .hasMessageContaining("not valid UTF-8").hasMessageContaining("csv.encoding");
            assertThat(rows).isNotEmpty().hasSizeLessThan(5001);
            assertThat(rows.get(0).cells()).containsExactly("1", "fine row");
        }
    }

    @Test
    void anUnclosedQuoteIsAFormatErrorNotASilentlyWrongRow() throws IOException {
        Path file = write("id,title\n1,fine\n2,\"never closed\n3,x\n", StandardCharsets.UTF_8);

        try (BatchCsvReader reader = BatchCsvReader.open(file, BatchMapping.Csv.DEFAULT)) {
            assertThat(reader.next().cells()).containsExactly("1", "fine");
            assertThatThrownBy(reader::next).isInstanceOf(BatchCsvReader.FormatException.class)
                    .hasMessageContaining("not well-formed CSV after line 2");
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
