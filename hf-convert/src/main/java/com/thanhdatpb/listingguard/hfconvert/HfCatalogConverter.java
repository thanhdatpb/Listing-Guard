package com.thanhdatpb.listingguard.hfconvert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Converts the Hugging Face dataset {@code illeatmyhat/uspto-trademarks} (ADR-030) to one {@code jsonl-1} file with
 * DuckDB: for each serial number its latest {@code case_file} row, its Nice classes from {@code intl_class}, filtered by
 * {@link HfConvertFilters}. Nothing here touches a database of Listing Guard.
 *
 * <p><b>Latest row wins.</b> The dataset says each case file appears once, but its repository can hold files of two
 * builds at the same time (on 2026-11-16, {@code data/case_file/data_1} and {@code data_2} of the 2026-10-02 build sit
 * next to {@code data_0} of the 2026-11-02 build). So the rows of a serial number are ordered by
 * {@code file_dt_source}, then {@code file_name_source} (the USPTO file that produced the row, which the dataset carries
 * on every row), and the latest wins. Identical rows count once; two different rows with the same latest lineage are a
 * record error ({@code conflicting_duplicate}; rows are compared by DuckDB's 64-bit {@code hash} of the columns read, so
 * a mark's text is never held for the serial numbers that are dropped). The classes of a serial number are the {@code intl_class} rows with the
 * same lineage as the row that won; a serial number whose class rows all have another lineage is
 * {@code class_lineage_mismatch}.
 *
 * <p><b>The checks</b>, in this order; a serial number is counted once, under the first it fails: a record error
 * ({@code invalid_serial}, {@code conflicting_duplicate}), not live ({@code deadRemoved}), the drawing code when the
 * filters want text ({@code missing_drawing_code}, {@code logo_only}, {@code no_drawing}, {@code unknown_drawing_code}),
 * the classes ({@code class_lineage_mismatch}, {@code no_valid_nice_class}, {@code class_not_included}), the year when
 * the filters name one ({@code no_date}, {@code before_min_year}), then the mark ({@code blank_mark},
 * {@code mark_too_long}, {@code mark_empty_after_normalization}, {@code mark_too_long_after_normalization},
 * {@code mark_too_short}). The two normalization checks drop what {@code trademark-import} would refuse a whole catalog
 * for.
 *
 * <p><b>No network unless allowed.</b> A remote input is refused before DuckDB is opened. For a local input DuckDB is
 * opened with extension auto-install and auto-load off, {@code allowed_directories} set to the input and the temporary
 * folder, {@code enable_external_access=false} and the configuration locked, so no SQL of this run can read a URL or
 * another folder. With the network allowed it loads {@code httpfs} (downloaded once from DuckDB's extension repository).
 *
 * <p>The output is sorted by serial number, so two runs over the same files give the same bytes.
 */
public final class HfCatalogConverter {

    public static final String INVALID_SERIAL = "invalid_serial";
    public static final String CONFLICTING_DUPLICATE = "conflicting_duplicate";
    public static final String NOT_LIVE = "not_live";
    public static final String MISSING_DRAWING_CODE = "missing_drawing_code";
    public static final String LOGO_ONLY = "logo_only";
    public static final String NO_DRAWING = "no_drawing";
    public static final String UNKNOWN_DRAWING_CODE = "unknown_drawing_code";
    public static final String CLASS_LINEAGE_MISMATCH = "class_lineage_mismatch";
    public static final String NO_VALID_NICE_CLASS = "no_valid_nice_class";
    public static final String CLASS_NOT_INCLUDED = "class_not_included";
    public static final String NO_DATE = "no_date";
    public static final String BEFORE_MIN_YEAR = "before_min_year";
    public static final String BLANK_MARK = "blank_mark";
    public static final String MARK_TOO_LONG = "mark_too_long";
    public static final String MARK_EMPTY_AFTER_NORMALIZATION = "mark_empty_after_normalization";
    public static final String MARK_TOO_LONG_AFTER_NORMALIZATION = "mark_too_long_after_normalization";
    public static final String MARK_TOO_SHORT = "mark_too_short";

    /** The license line of the manifest: the dataset card's words, and who rebuilt the data. At most 200 characters. */
    public static final String LICENSE = "US government work, public domain (per the dataset card); a third-party rebuild "
            + "of USPTO bulk XML, not checked by Listing Guard";

    private static final List<String> RECORD_ERRORS = List.of(INVALID_SERIAL, CONFLICTING_DUPLICATE);
    private static final Pattern MEMORY = Pattern.compile("[0-9]{1,6}(\\.[0-9]{1,3})?\\s?(KB|MB|GB|TB|KiB|MiB|GiB|TiB)");
    private static final int BUFFER_CHARS = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param allowNetwork read a remote input ({@code hf://}); off by default
     * @param strict       stop at the first problem instead of reporting {@code INCOMPLETE}
     * @param memoryLimit  DuckDB's {@code memory_limit}, for example {@code 2GB}; it spills to {@code tempDir} beyond it
     * @param threads      DuckDB's {@code threads}
     * @param tempDir      a folder DuckDB may spill to; it must exist
     */
    public record Settings(boolean allowNetwork, boolean strict, String memoryLimit, int threads, Path tempDir) {
        public Settings {
            if (memoryLimit == null || !MEMORY.matcher(memoryLimit).matches()) {
                throw new HfConvertException("The memory limit must look like 2GB or 1500MB, got '" + memoryLimit + "'.");
            }
            if (threads < 1 || threads > 64) {
                throw new HfConvertException("threads must be from 1 to 64, got " + threads + ".");
            }
            if (tempDir == null) {
                throw new HfConvertException("A temporary folder is required.");
            }
        }
    }

    private final HfConvertFilters filters;
    private final TextNormalizer normalizer;
    private final String rulesetVersion;
    private final Settings settings;

    /**
     * @param normalizer     the normalizer of the active trademark ruleset: the import normalizes each mark with it
     * @param rulesetVersion the version of that ruleset, for the report
     */
    public HfCatalogConverter(HfConvertFilters filters, TextNormalizer normalizer, String rulesetVersion,
                              Settings settings) {
        this.filters = filters;
        this.normalizer = normalizer;
        this.rulesetVersion = rulesetVersion;
        this.settings = settings;
    }

    /**
     * Reads the dataset and writes {@code jsonl-1} (UTF-8, LF) to {@code jsonl}. The stream is not closed; the output is
     * flushed. On an exception the output holds a partial result and must be discarded.
     *
     * @throws HfConvertException a remote input without the network allowed, a dataset the filters were not written
     *                            for, a missing table, a DuckDB error, or (strict) the first problem
     */
    public HfConversionReport convert(HfDataset dataset, OutputStream jsonl) throws IOException {
        long started = System.nanoTime();
        if (dataset.remote() && !settings.allowNetwork()) {
            throw new HfConvertException("The input " + dataset.label() + " needs the network, which is off; download "
                    + "the files to catalogs/raw/ (docs/trademark-catalog.md, \"Hugging Face source\") or allow it with "
                    + "--allow-network (HF_CONVERT_ALLOW_NETWORK=true).");
        }
        if (dataset.remote() && !filters.dataset().equals(dataset.datasetId())) {
            throw new HfConvertException("The filters " + filters.version() + " are for the dataset " + filters.dataset()
                    + ", not " + dataset.datasetId() + ".");
        }
        List<HfDataset.ParquetFile> localCaseFiles = List.of();
        List<HfDataset.ParquetFile> localIntlFiles = List.of();
        if (dataset instanceof HfDataset.Local local) {
            localCaseFiles = local.files(HfDataset.CASE_FILE);
            localIntlFiles = local.files(HfDataset.INTL_CLASS);
        }
        try (Connection connection = open(dataset)) {
            Run run = new Run(connection, dataset);
            run.caseFiles = dataset.remote() ? run.remoteFiles(HfDataset.CASE_FILE) : localCaseFiles;
            run.intlFiles = dataset.remote() ? run.remoteFiles(HfDataset.INTL_CLASS) : localIntlFiles;
            return run.execute(jsonl, started);
        } catch (SQLException e) {
            throw new HfConvertException("DuckDB failed: " + e.getMessage(), e);
        }
    }

    // --- DuckDB ---

    /** A new in-memory DuckDB, configured before any table is read. */
    Connection open(HfDataset dataset) throws SQLException {
        Properties properties = new Properties();
        // set at start-up, before any statement: nothing may be fetched behind the job's back
        properties.setProperty("autoinstall_known_extensions", Boolean.toString(settings.allowNetwork()));
        properties.setProperty("autoload_known_extensions", Boolean.toString(settings.allowNetwork()));
        Connection connection = DriverManager.getConnection("jdbc:duckdb:", properties);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET memory_limit = " + literal(settings.memoryLimit()));
            statement.execute("SET threads = " + settings.threads());
            statement.execute("SET temp_directory = " + literal(HfDataset.duckdbPath(settings.tempDir())));
            statement.execute("SET preserve_insertion_order = false");
            if (settings.allowNetwork()) {
                if (dataset.remote()) {
                    statement.execute("INSTALL httpfs");
                    statement.execute("LOAD httpfs");
                }
            } else {
                List<String> allowed = new ArrayList<>();
                if (dataset instanceof HfDataset.Local local) {
                    allowed.add(HfDataset.duckdbPath(local.dir()) + "/");
                }
                allowed.add(HfDataset.duckdbPath(settings.tempDir()) + "/");
                statement.execute("SET allowed_directories = "
                        + allowed.stream().map(HfCatalogConverter::literal).collect(Collectors.joining(", ", "[", "]")));
                statement.execute("SET enable_external_access = false");
            }
            statement.execute("SET lock_configuration = true");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /** A SQL string literal. */
    static String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    private static String list(List<HfDataset.ParquetFile> files) {
        return files.stream().map(f -> literal(f.duckdbPath())).collect(Collectors.joining(", ", "[", "]"));
    }

    // --- one run ---

    private final class Run {
        private final Connection connection;
        private final HfDataset dataset;
        private final List<String> problems = new ArrayList<>();
        private final Map<String, Long> filtered = new TreeMap<>();
        private final Map<String, Long> errors = new TreeMap<>();
        private final Map<String, Long> deadByStatus = new TreeMap<>();
        private List<HfDataset.ParquetFile> caseFiles;
        private List<HfDataset.ParquetFile> intlFiles;
        private long recordErrors;
        private long deadRemoved;
        private long written;
        private long ignoredClassCodes;

        Run(Connection connection, HfDataset dataset) {
            this.connection = connection;
            this.dataset = dataset;
        }

        HfConversionReport execute(OutputStream jsonl, long started) throws SQLException, IOException {
            List<HfConversionReport.FileReport> files = new ArrayList<>();
            long caseRows = rows(caseFiles, files);
            long intlRows = rows(intlFiles, files);

            HfConversionReport.Lineage lineage = lineage();
            HfConversionReport.LookupCheck lookup = lookupCheck();

            long distinct = scalar("SELECT count(DISTINCT serial_no) + max(CASE WHEN serial_no IS NULL THEN 1 ELSE 0 END)"
                    + " FROM read_parquet(" + list(caseFiles) + ")");
            if (lineage != null && lineage.caseFileRows() != null && lineage.caseFileRows() != distinct) {
                problem("The case files hold " + distinct + " serial numbers; " + HfDataset.LINEAGE + " says "
                        + lineage.caseFileRows() + ". A file of the build may be missing.");
            }
            if (lineage != null && lineage.intlClassRows() != null && intlRows < lineage.intlClassRows()) {
                problem("The intl_class files hold " + intlRows + " rows; " + HfDataset.LINEAGE + " says "
                        + lineage.intlClassRows() + ". A file of the build may be missing.");
            }

            classify(caseRows > distinct);
            counts();
            if (recordErrors > 0) {
                problem(recordErrors + " serial numbers have a record error " + errors + ".");
            }

            MessageDigest digest = newDigest();
            CountingOutput out = new CountingOutput(new DigestOutputStream(jsonl, digest));
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), BUFFER_CHARS);
            writeCandidates(writer);
            writer.flush();

            Completeness completeness = problems.isEmpty() ? Completeness.COMPLETE : Completeness.INCOMPLETE;
            String datasetId = dataset.datasetId() != null ? dataset.datasetId() : filters.dataset();
            HfConversionReport.Source source = new HfConversionReport.Source(
                    sourceName(datasetId, lineage), url(datasetId, dataset.revision()), LICENSE,
                    lineage == null ? null : lineage.dataCurrentThrough());
            return new HfConversionReport(completeness, settings.strict(), filters.version(), filters.contentHash(),
                    rulesetVersion, filters.minYear(), dataset.label(), dataset.remote(), source, lineage, files,
                    caseRows, intlRows, caseRows - distinct, distinct, written, recordErrors, deadRemoved,
                    ignoredClassCodes, filtered, errors, deadByStatus, lookup, problems, duckdbVersion(),
                    (System.nanoTime() - started) / 1_000_000, out.count, HexFormat.of().formatHex(digest.digest()));
        }

        private void problem(String message) {
            if (settings.strict()) {
                throw new HfConvertException(message);
            }
            problems.add(message);
        }

        // --- the files ---

        List<HfDataset.ParquetFile> remoteFiles(String table) throws SQLException {
            HfDataset.Remote remote = (HfDataset.Remote) dataset;
            List<HfDataset.ParquetFile> files = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT file FROM glob(" + literal(remote.glob(table))
                         + ") ORDER BY file")) {
                while (rs.next()) {
                    String file = rs.getString(1);
                    int data = file.indexOf("/data/");
                    files.add(new HfDataset.ParquetFile(table, file, data < 0 ? file : file.substring(data + 1), null,
                            null));
                }
            }
            if (files.isEmpty()) {
                throw new HfConvertException(remote.glob(table) + " matches no file.");
            }
            return files;
        }

        private long rows(List<HfDataset.ParquetFile> files, List<HfConversionReport.FileReport> reports)
                throws SQLException {
            long total = 0;
            for (HfDataset.ParquetFile file : files) {
                long rows = scalar("SELECT num_rows FROM parquet_file_metadata(" + literal(file.duckdbPath()) + ")");
                reports.add(new HfConversionReport.FileReport(file.table(), file.name(), file.bytes(), file.sha256(),
                        rows));
                total += rows;
            }
            return total;
        }

        // --- what the dataset says about itself ---

        private String readText(String relative) throws IOException {
            if (dataset instanceof HfDataset.Local local) {
                Path path = local.dir().resolve(relative);
                return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT content FROM read_text("
                         + literal(dataset.pathOf(relative)) + ")")) {
                return rs.next() ? rs.getString(1) : null;
            } catch (SQLException e) {
                return null; // not in the repository (DuckDB reports a missing remote file as an error)
            }
        }

        private HfConversionReport.Lineage lineage() throws IOException {
            String text = readText(HfDataset.LINEAGE);
            if (text == null) {
                problem(HfDataset.LINEAGE + " is missing: the build and the date of the data (source_date) are unknown. "
                        + "Download it with the data.");
                return null;
            }
            JsonNode root;
            try {
                root = JSON.readTree(text);
            } catch (IOException e) {
                problem(HfDataset.LINEAGE + " is not valid JSON.");
                return null;
            }
            String through = textOf(root, "data_current_through");
            if (through == null || !isDate(through)) {
                problem(HfDataset.LINEAGE + " has no date data_current_through: source_date is unknown.");
                through = null;
            }
            JsonNode counts = root.path("row_counts");
            return new HfConversionReport.Lineage(textOf(root, "build_id"), textOf(root, "built_at"), through,
                    textOf(root, "annual_cutoff"), longOf(counts, HfDataset.CASE_FILE),
                    longOf(counts, HfDataset.INTL_CLASS));
        }

        private HfConversionReport.LookupCheck lookupCheck() throws IOException {
            String text = readText(HfDataset.STATUS_LOOKUP);
            if (text == null) {
                problem(HfDataset.STATUS_LOOKUP + " is missing: the status codes of the filters were not checked "
                        + "against the dataset's lookup. Download it with the data.");
                return new HfConversionReport.LookupCheck(false, List.of(), List.of());
            }
            Map<String, String> indicators;
            try {
                indicators = StatusLookup.parse(text);
            } catch (HfConvertException e) {
                problem(HfDataset.STATUS_LOOKUP + ": " + e.getMessage());
                return new HfConversionReport.LookupCheck(false, List.of(), List.of());
            }
            List<String> notLive = new ArrayList<>();
            for (String code : filters.statusCodes().keySet()) {
                String indicator = indicators.get(code);
                if (indicator == null || !StatusLookup.isLive(indicator)) {
                    notLive.add(code);
                }
            }
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, String> entry : indicators.entrySet()) {
                if (StatusLookup.isLive(entry.getValue()) && filters.statusOf(entry.getKey()) == null) {
                    missing.add(entry.getKey());
                }
            }
            if (!notLive.isEmpty()) {
                problem("The filters keep status codes that " + HfDataset.STATUS_LOOKUP + " does not list as live: "
                        + notLive + ".");
            }
            return new HfConversionReport.LookupCheck(true, notLive, missing);
        }

        // --- the classification, in SQL ---

        /**
         * Two temporary tables, neither holding a mark: {@code latest} (the winning row of each serial number) and
         * {@code classified} (its reason, or null for a candidate, with its classes). The mark text is read only for
         * the candidates, in {@link #writeCandidates}, so memory stays small however long the marks are.
         */
        private void classify(boolean duplicates) throws SQLException {
            String latest;
            if (duplicates) {
                // one group per (serial number, lineage); two different rows in a group differ in their hash
                latest = "CREATE TEMP TABLE latest AS WITH g AS (SELECT serial_no, file_dt_source AS dt, "
                        + "file_name_source AS fn, min(h) AS hmin, max(h) AS hmax, any_value(status_cd) AS status_cd, "
                        + "any_value(mark_draw_cd) AS draw, any_value(filing_dt) AS filing_dt, "
                        + "any_value(registration_dt) AS registration_dt FROM (SELECT serial_no, file_dt_source, "
                        + "file_name_source, status_cd, mark_draw_cd, filing_dt, registration_dt, "
                        + "hash(status_cd, mark_id_char, mark_draw_cd, filing_dt, registration_dt) AS h "
                        + "FROM read_parquet(" + list(caseFiles) + ")) GROUP BY serial_no, file_dt_source, "
                        + "file_name_source) "
                        + "SELECT serial_no, hmin <> hmax AS conflict, dt, fn, status_cd, draw, filing_dt, "
                        + "registration_dt FROM g QUALIFY row_number() OVER (PARTITION BY serial_no "
                        + "ORDER BY dt DESC NULLS LAST, fn DESC NULLS LAST) = 1";
            } else {
                latest = "CREATE TEMP TABLE latest AS SELECT serial_no, FALSE AS conflict, file_dt_source AS dt, "
                        + "file_name_source AS fn, status_cd, mark_draw_cd AS draw, filing_dt, registration_dt "
                        + "FROM read_parquet(" + list(caseFiles) + ")";
            }

            StringBuilder early = new StringBuilder("CASE ");
            when(early, "serial_no IS NULL OR NOT regexp_full_match(serial_no, '[0-9]{8}')", INVALID_SERIAL);
            when(early, "conflict", CONFLICTING_DUPLICATE);
            when(early, "status_cd IS NULL OR NOT list_contains(" + filters.statusCodes().keySet().stream()
                    .map(HfCatalogConverter::literal).collect(Collectors.joining(", ", "[", "]")) + ", status_cd)",
                    NOT_LIVE);
            if (filters.textOnly()) {
                when(early, "draw IS NULL OR trim(draw) = ''", MISSING_DRAWING_CODE);
                when(early, "left(trim(draw), 1) = '2'", LOGO_ONLY);
                when(early, "left(trim(draw), 1) = '6'", NO_DRAWING);
                when(early, "left(trim(draw), 1) NOT IN ('1', '3', '4', '5')", UNKNOWN_DRAWING_CODE);
            }
            early.append("ELSE NULL END");

            StringBuilder late = new StringBuilder("CASE ");
            when(late, "NOT lineage_match AND any_class", CLASS_LINEAGE_MISMATCH);
            when(late, "len(classes) = 0", NO_VALID_NICE_CLASS);
            when(late, "NOT list_has_any(classes, " + filters.niceClasses().stream().map(String::valueOf)
                    .collect(Collectors.joining(", ", "[", "]::INTEGER[]")) + ")", CLASS_NOT_INCLUDED);
            if (filters.minYear() != null) {
                when(late, "coalesce(registration_dt, filing_dt) IS NULL", NO_DATE);
                when(late, "year(coalesce(registration_dt, filing_dt)) < " + filters.minYear(), BEFORE_MIN_YEAR);
            }
            late.append("ELSE NULL END");

            String valid = "(regexp_full_match(intl_class_cd, '[0-9]{1,3}') "
                    + "AND TRY_CAST(intl_class_cd AS INTEGER) BETWEEN 1 AND 45)";
            // classes are read only for the serial numbers that passed the status and drawing checks
            String classified = "CREATE TEMP TABLE classified AS "
                    + "WITH pre AS (SELECT *, " + early + " AS early FROM latest), "
                    + "alive AS (SELECT serial_no FROM pre WHERE early IS NULL), "
                    + "ic AS (SELECT i.serial_no, i.file_dt_source AS dt, i.file_name_source AS fn, "
                    + "list(DISTINCT TRY_CAST(i.intl_class_cd AS INTEGER)) FILTER (WHERE " + valid + ") AS classes, "
                    + "count(*) FILTER (WHERE i.intl_class_cd IS NULL OR NOT " + valid + ") AS ignored "
                    + "FROM read_parquet(" + list(intlFiles) + ") i SEMI JOIN alive a ON i.serial_no = a.serial_no "
                    + "GROUP BY i.serial_no, i.file_dt_source, i.file_name_source), "
                    + "ic_any AS (SELECT DISTINCT serial_no FROM ic), "
                    + "joined AS (SELECT p.*, list_sort(coalesce(m.classes, []::INTEGER[])) AS classes, "
                    + "coalesce(m.ignored, 0) AS ignored, m.serial_no IS NOT NULL AS lineage_match, "
                    + "a.serial_no IS NOT NULL AS any_class FROM pre p "
                    + "LEFT JOIN ic m ON m.serial_no = p.serial_no AND m.dt IS NOT DISTINCT FROM p.dt "
                    + "AND m.fn IS NOT DISTINCT FROM p.fn "
                    + "LEFT JOIN ic_any a ON a.serial_no = p.serial_no) "
                    + "SELECT serial_no, status_cd, reason, dt, fn, "
                    + "CASE WHEN reason IS NULL THEN array_to_string(classes, ',') END AS classes, "
                    + "CASE WHEN reason IS NULL THEN ignored ELSE 0 END AS ignored "
                    + "FROM (SELECT *, coalesce(early, " + late + ") AS reason FROM joined)";
            try (Statement statement = connection.createStatement()) {
                statement.execute(latest);
                statement.execute(classified);
                statement.execute("DROP TABLE latest");
            }
        }

        private static void when(StringBuilder reason, String condition, String code) {
            reason.append("WHEN ").append(condition).append(" THEN '").append(code).append("' ");
        }

        private void counts() throws SQLException {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT reason, count(*) FROM classified "
                         + "WHERE reason IS NOT NULL GROUP BY reason")) {
                while (rs.next()) {
                    String reason = rs.getString(1);
                    long count = rs.getLong(2);
                    if (RECORD_ERRORS.contains(reason)) {
                        errors.put(reason, count);
                        recordErrors += count;
                    } else if (NOT_LIVE.equals(reason)) {
                        deadRemoved += count;
                    } else {
                        filtered.put(reason, count);
                    }
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT CASE WHEN status_cd IS NULL THEN 'missing' "
                         + "WHEN regexp_full_match(status_cd, '[0-9]{3}') THEN status_cd ELSE 'invalid' END, count(*) "
                         + "FROM classified WHERE reason = '" + NOT_LIVE + "' GROUP BY 1")) {
                while (rs.next()) {
                    deadByStatus.put(rs.getString(1), rs.getLong(2));
                }
            }
        }

        // --- the marks, in Java: the import's own normalization ---

        private void writeCandidates(Writer writer) throws SQLException, IOException {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("WITH c AS (SELECT serial_no, status_cd, dt, fn, classes, "
                         + "ignored FROM classified WHERE reason IS NULL), "
                         + "f AS (SELECT DISTINCT r.serial_no, r.file_dt_source AS dt, r.file_name_source AS fn, "
                         + "r.mark_id_char AS mark FROM read_parquet(" + list(caseFiles) + ") r "
                         + "SEMI JOIN c ON r.serial_no = c.serial_no) "
                         + "SELECT c.serial_no, c.status_cd, f.mark, c.classes, c.ignored FROM c JOIN f "
                         + "ON f.serial_no = c.serial_no AND f.dt IS NOT DISTINCT FROM c.dt "
                         + "AND f.fn IS NOT DISTINCT FROM c.fn ORDER BY c.serial_no")) {
                while (rs.next()) {
                    String serial = rs.getString(1);
                    MarkStatus status = filters.statusOf(rs.getString(2));
                    String mark = rs.getString(3);
                    String display = mark == null ? "" : mark.strip();
                    if (display.isEmpty()) {
                        drop(BLANK_MARK);
                        continue;
                    }
                    if (display.length() > CatalogImport.MAX_MARK_CHARS) {
                        drop(MARK_TOO_LONG);
                        continue;
                    }
                    String normalized = normalizer.normalizeMark(display);
                    if (normalized.isEmpty()) {
                        drop(MARK_EMPTY_AFTER_NORMALIZATION);
                        continue;
                    }
                    if (normalized.length() > CatalogImport.MAX_MARK_CHARS) {
                        drop(MARK_TOO_LONG_AFTER_NORMALIZATION);
                        continue;
                    }
                    String compact = normalized.replace(" ", "");
                    if (compact.codePointCount(0, compact.length()) < filters.minMarkChars()) {
                        drop(MARK_TOO_SHORT);
                        continue;
                    }
                    ObjectNode json = JSON.createObjectNode();
                    json.put("mark", display);
                    json.put("status", status.name());
                    ArrayNode niceClasses = json.putArray("nice_classes");
                    for (String niceClass : rs.getString(4).split(",")) {
                        niceClasses.add(Integer.parseInt(niceClass));
                    }
                    json.put("source_id", serial);
                    writer.write(JSON.writeValueAsString(json));
                    writer.write('\n');
                    written++;
                    ignoredClassCodes += rs.getLong(5);
                }
            }
        }

        private void drop(String reason) {
            filtered.merge(reason, 1L, Long::sum);
        }

        // --- helpers ---

        private long scalar(String sql) throws SQLException {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        }

        private String duckdbVersion() throws SQLException {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT version()")) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String sourceName(String datasetId, HfConversionReport.Lineage lineage) {
        String build = lineage == null || lineage.buildId() == null ? "" : " " + lineage.buildId();
        return "USPTO trademark case files, Hugging Face " + datasetId + build + " (converted by hf-convert)";
    }

    private static String url(String datasetId, String revision) {
        return "https://huggingface.co/datasets/" + datasetId + (revision == null ? "" : "/tree/" + revision);
    }

    private static String textOf(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static Long longOf(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() ? value.asLong() : null;
    }

    private static boolean isDate(String text) {
        try {
            LocalDate.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads {@code lookups/status_codes.csv}: {@code code,description,live_dead_indicator}, RFC 4180 quoting. */
    static final class StatusLookup {

        private StatusLookup() {
        }

        static boolean isLive(String indicator) {
            return indicator.equals("Live") || indicator.startsWith("Live/");
        }

        /** Code to indicator, sorted by code. */
        static SortedMap<String, String> parse(String text) {
            List<List<String>> rows = csv(text);
            if (rows.isEmpty()) {
                throw new HfConvertException("the file is empty");
            }
            List<String> header = rows.get(0);
            int code = header.indexOf("code");
            int indicator = header.indexOf("live_dead_indicator");
            if (code < 0 || indicator < 0) {
                throw new HfConvertException("the header has no column code or live_dead_indicator: " + header);
            }
            SortedMap<String, String> map = new TreeMap<>();
            for (List<String> row : rows.subList(1, rows.size())) {
                if (row.size() == 1 && row.get(0).isEmpty()) {
                    continue;
                }
                if (row.size() <= Math.max(code, indicator)) {
                    throw new HfConvertException("a row has " + row.size() + " columns, the header " + header.size());
                }
                map.put(row.get(code).strip(), row.get(indicator).strip());
            }
            return map;
        }

        private static List<List<String>> csv(String text) {
            String body = text.startsWith("﻿") ? text.substring(1) : text;
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < body.length() && body.charAt(i + 1) == '"') {
                            cell.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < body.length() && body.charAt(i + 1) == '\n') {
                        i++;
                    }
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                rows.add(row);
            }
            return rows;
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
