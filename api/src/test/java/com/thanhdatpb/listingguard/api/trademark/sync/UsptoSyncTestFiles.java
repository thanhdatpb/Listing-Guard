package com.thanhdatpb.listingguard.api.trademark.sync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Synthetic USPTO Trademark Applications Daily XML (DTD V2.0 shape) and zips of it for the sync tests. Every mark and serial
 * number is invented; nothing here was checked against a real USPTO file.
 */
final class UsptoSyncTestFiles {

    private UsptoSyncTestFiles() {
    }

    /** One case file; {@code niceClass} like {@code 025}. */
    static String caseFile(String serial, String transactionDate, String mark, String drawing, String status,
                           String niceClass) {
        return "<case-file><serial-number>" + serial + "</serial-number><registration-number>0000000</registration-number>"
                + "<transaction-date>" + transactionDate + "</transaction-date>"
                + "<case-file-header><filing-date>20200101</filing-date><status-code>" + status + "</status-code>"
                + "<mark-identification>" + mark + "</mark-identification><mark-drawing-code>" + drawing
                + "</mark-drawing-code></case-file-header><classifications><classification>"
                + "<international-code>" + niceClass + "</international-code></classification></classifications>"
                + "</case-file>";
    }

    /** A live, registered word mark in class 25. */
    static String live(String serial, String date, String mark) {
        return caseFile(serial, date, mark, "4000", "700", "025");
    }

    /** The same serial number, cancelled. */
    static String cancelled(String serial, String date, String mark) {
        return caseFile(serial, date, mark, "4000", "710", "025");
    }

    static String xml(String... caseFiles) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<trademark-applications-daily><version>"
                + "<version-no>2.0</version-no><version-date>20041108</version-date></version><application-information>"
                + "<file-segments><file-segment>TRMK</file-segment><action-keys><action-key>NA</action-key>"
                + String.join("", caseFiles)
                + "</action-keys></file-segments></application-information></trademark-applications-daily>";
    }

    /** A zip with one entry, the XML under the zip's own name with {@code .xml}. */
    static byte[] zip(String zipName, String xml) {
        return zip(zipName.replaceAll("(?i)\\.zip$", ".xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] zip(String entryName, byte[] content) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content);
            out.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static Path write(Path dir, String name, byte[] bytes) {
        try {
            Files.createDirectories(dir);
            return Files.write(dir.resolve(name), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
