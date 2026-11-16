package com.thanhdatpb.listingguard.hfconvert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a small synthetic copy of the Hugging Face dataset: Parquet files made by DuckDB's {@code COPY ... TO} with the
 * column names and types of the real tables (ADR-030, "Column names"), plus {@code profile/build_lineage.json} and
 * {@code lookups/status_codes.csv}. Every mark is an invented word; every serial number is made up.
 */
final class HfFixture {

    static final LocalDate BUILD = LocalDate.of(2026, 10, 31);
    static final String DAILY = "apc261031.zip";

    /** One {@code case_file} row; the lineage defaults to the latest daily file. */
    record CaseRow(String serial, String mark, String status, String draw, LocalDate filing, LocalDate registration,
                   LocalDate dtSource, String fileSource) {

        static CaseRow live(String serial, String mark, String status) {
            return new CaseRow(serial, mark, status, "4", LocalDate.of(2020, 1, 2), LocalDate.of(2021, 3, 4), BUILD,
                    DAILY);
        }

        CaseRow withDraw(String value) {
            return new CaseRow(serial, mark, status, value, filing, registration, dtSource, fileSource);
        }

        CaseRow withDates(LocalDate filingDate, LocalDate registrationDate) {
            return new CaseRow(serial, mark, status, draw, filingDate, registrationDate, dtSource, fileSource);
        }

        CaseRow withLineage(LocalDate date, String file) {
            return new CaseRow(serial, mark, status, draw, filing, registration, date, file);
        }
    }

    /** One {@code intl_class} row. */
    record ClassRow(String serial, int seq, String code, LocalDate dtSource, String fileSource) {

        static ClassRow of(String serial, int seq, String code) {
            return new ClassRow(serial, seq, code, BUILD, DAILY);
        }
    }

    private HfFixture() {
    }

    static void caseFile(Path dataset, String fileName, List<CaseRow> rows) throws IOException, SQLException {
        Path folder = Files.createDirectories(dataset.resolve("data/case_file"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE t (serial_no VARCHAR, registration_no VARCHAR, mark_id_char VARCHAR, "
                        + "filing_dt DATE, registration_dt DATE, status_cd VARCHAR, status_dt DATE, "
                        + "mark_draw_cd VARCHAR, std_char_claimed_in BOOLEAN, file_dt_source DATE, "
                        + "file_name_source VARCHAR)");
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO t VALUES (?, NULL, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)")) {
                for (CaseRow row : rows) {
                    p.setString(1, row.serial());
                    p.setString(2, row.mark());
                    date(p, 3, row.filing());
                    date(p, 4, row.registration());
                    p.setString(5, row.status());
                    date(p, 6, row.dtSource());
                    p.setString(7, row.draw());
                    date(p, 8, row.dtSource());
                    p.setString(9, row.fileSource());
                    p.execute();
                }
            }
            copy(c, folder.resolve(fileName + ".parquet"));
        }
    }

    static void intlClass(Path dataset, String fileName, List<ClassRow> rows) throws IOException, SQLException {
        Path folder = Files.createDirectories(dataset.resolve("data/intl_class"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE t (serial_no VARCHAR, class_seq INTEGER, intl_class_cd VARCHAR, "
                        + "file_dt_source DATE, file_name_source VARCHAR)");
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO t VALUES (?, ?, ?, ?, ?)")) {
                for (ClassRow row : rows) {
                    p.setString(1, row.serial());
                    p.setInt(2, row.seq());
                    p.setString(3, row.code());
                    date(p, 4, row.dtSource());
                    p.setString(5, row.fileSource());
                    p.execute();
                }
            }
            copy(c, folder.resolve(fileName + ".parquet"));
        }
    }

    static void lineage(Path dataset, long caseFileRows, long intlClassRows) throws IOException {
        Files.createDirectories(dataset.resolve("profile"));
        Files.writeString(dataset.resolve(HfDataset.LINEAGE), """
                {
                  "build_id": "build-20261102-000000",
                  "parser_version": "1",
                  "annual_cutoff": "2025-12-31",
                  "data_current_through": "%s",
                  "built_at": "2026-11-02T07:06:59+00:00",
                  "row_counts": {"case_file": %d, "intl_class": %d, "owner": 1}
                }
                """.formatted(BUILD, caseFileRows, intlClassRows), StandardCharsets.UTF_8);
    }

    /** A status lookup that lists every code of the filters as live, plus {@code extra} (code to indicator). */
    static void lookup(Path dataset, HfConvertFilters filters, Map<String, String> extra) throws IOException {
        Map<String, String> rows = new TreeMap<>();
        filters.statusCodes().forEach((code, status) -> rows.put(code, switch (status) {
            case REGISTERED -> "Live/Registered";
            default -> "Live/Pending";
        }));
        rows.putAll(extra);
        List<String> lines = new ArrayList<>();
        lines.add("code,description,live_dead_indicator");
        rows.forEach((code, indicator) -> lines.add(code + ",\"A SYNTHETIC STATUS, " + code + "\"," + indicator));
        Files.createDirectories(dataset.resolve("lookups"));
        Files.writeString(dataset.resolve(HfDataset.STATUS_LOOKUP), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
    }

    private static void date(PreparedStatement p, int index, LocalDate value) throws SQLException {
        if (value == null) {
            p.setNull(index, Types.DATE);
        } else {
            p.setDate(index, Date.valueOf(value));
        }
    }

    private static void copy(Connection c, Path target) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("COPY t TO " + HfCatalogConverter.literal(HfDataset.duckdbPath(target)) + " (FORMAT parquet)");
        }
    }
}
