package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap.Mapped;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Synthetic USPTO Trademark Applications Daily XML for the tests, written by hand to follow the public DTD V2.0
 * (ADR-009): the marks and serial numbers are invented, and nothing was checked against a real USPTO file.
 */
final class UsptoTestXml {

    static final String STATUS_MAP = "uspto/uspto-status-map-2026-11.yaml";
    static final String DEFAULT_DATE = "20241101";

    /** The two shipped patterns, repeated here so a test can write filters of its own. */
    private static final String FILE_NAME_DATES = """
            file_name_dates:
              - '(?<![0-9])(?<year>[0-9]{4})(?<month>[0-9]{2})(?<day>[0-9]{2})(?![0-9])'
              - '(?<![A-Za-z0-9])apc(?<yy>[0-9]{2})(?<month>[0-9]{2})(?<day>[0-9]{2})(?![0-9])'
            """;

    private UsptoTestXml() {
    }

    static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String element(String name, String value) {
        return value == null ? "" : "<" + name + ">" + esc(value) + "</" + name + ">";
    }

    /** A case-file in the order of the DTD, with the default transaction date; a null value leaves the element out. */
    static String caseFile(String serial, String mark, String drawing, String status, String... classes) {
        return caseFileOn(serial, DEFAULT_DATE, mark, drawing, status, classes);
    }

    static String caseFileOn(String serial, String transactionDate, String mark, String drawing, String status,
                             String... classes) {
        String classification = "<classification><international-code-total-no>" + classes.length
                + "</international-code-total-no>"
                + java.util.Arrays.stream(classes).map(c -> element("international-code", c)).collect(Collectors.joining())
                // the status of the class: a decoy, the header status is the one that counts
                + "<status-code>6</status-code><status-date>20200101</status-date></classification>";
        return caseFileWith(serial, transactionDate, mark, drawing, status,
                "<classifications>" + classification + "</classifications>");
    }

    static String caseFileWith(String serial, String transactionDate, String mark, String drawing, String status,
                               String classifications) {
        return "<case-file>" + element("serial-number", serial)
                + "<registration-number>0000000</registration-number>" + element("transaction-date", transactionDate)
                + "<case-file-header><filing-date>20200101</filing-date><registration-date>00000000</registration-date>"
                + element("status-code", status) + "<status-date>20240101</status-date>"
                + element("mark-identification", mark) + element("mark-drawing-code", drawing)
                + "</case-file-header>"
                + "<case-file-statements><case-file-statement><type-code>GS0251</type-code><text>Clothing</text>"
                + "</case-file-statement></case-file-statements>"
                + "<foreign-applications><foreign-application><filing-date>20190101</filing-date>"
                + "<country>XX</country><registration-number>9999999</registration-number>"
                + "</foreign-application></foreign-applications>"
                + classifications
                + "<case-file-owners><case-file-owner><party-name>Example Owner</party-name><country>XX</country>"
                + "</case-file-owner></case-file-owners></case-file>";
    }

    static String fileVersion(String versionNo, String versionDate, String... caseFiles) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<trademark-applications-daily>"
                + "<version><version-no>" + versionNo + "</version-no><version-date>" + versionDate
                + "</version-date></version><application-information><file-segments>"
                + "<file-segment>TRMK</file-segment><action-keys><action-key>NA</action-key>"
                + String.join("", caseFiles) + "</action-keys></file-segments></application-information>"
                + "</trademark-applications-daily>";
    }

    static String file(String... caseFiles) {
        return fileVersion("2.0", "20041108", caseFiles);
    }

    static UsptoCatalogConverter.Source source(String name, String xml) {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return new UsptoCatalogConverter.Source() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public InputStream open() {
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    static UsptoStatusMap statusMap() {
        return UsptoStatusMap.loadClasspath(STATUS_MAP);
    }

    /** Every Nice class and every code that is the status of a mark in the map: only the order and the text filter act. */
    static UsptoConvertFilters permissive(UsptoStatusMap map) {
        List<String> live = map.entries().keySet().stream().filter(code -> map.outcome(code) instanceof Mapped).toList();
        return filters(IntStream.rangeClosed(1, 45).boxed().toList(), live, true);
    }

    static UsptoConvertFilters filters(Collection<Integer> classes, Collection<String> liveCodes, boolean textOnly) {
        return UsptoConvertFilters.parse("version: uspto-convert-filters-test\n"
                + "format: trademark-applications-daily V2.0\n"
                + "checked_at: 2026-11-11\n"
                + "source: {status: unverified, note: a test}\n"
                + "nice_classes: [" + classes.stream().map(String::valueOf).collect(Collectors.joining(", ")) + "]\n"
                + "text_only: " + textOnly + "\n"
                + "live_status_codes: [" + liveCodes.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", "))
                + "]\n" + FILE_NAME_DATES);
    }
}
