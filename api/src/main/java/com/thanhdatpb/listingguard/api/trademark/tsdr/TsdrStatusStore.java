package com.thanhdatpb.listingguard.api.trademark.tsdr;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The state of the TSDR status check in the database (ADR-009, "TSDR status check", V34): which marks to ask about, what the
 * USPTO answered, and the report's view of both. It reads the findings of recent trademark checks only for the source ids they
 * name and for when they were made, never their text, field, snippet, input hash or check id. It writes nothing but its own two
 * tables; the catalog tables are only read.
 */
@Component
public class TsdrStatusStore {

    /** Keep these enums equal to the {@code CHECK} constraints of V34. */
    public enum Mode { LIVE, OFFLINE }

    public enum RunStatus { RUNNING, DONE, INCOMPLETE, STOPPED, FAILED }

    public enum Source { API, FILE }

    public enum CallResult { OK, NOT_FOUND, RATE_LIMITED, TIMEOUT, REFUSED, ERROR, UNPARSEABLE }

    public enum StatusClass { LIVE, DEAD, UNKNOWN }

    /** A mark to ask about. {@code matches} is the number of recent findings that named it (0 for an unmatched pick). */
    public record Candidate(String sourceId, String serial, String display, long matches) {
    }

    /** One answer (or failure) to store. */
    public record Row(String serial, Source source, CallResult result, Integer httpStatus, Integer attempts,
                      String statusCode, String statusDescription, LocalDate statusDate, StatusClass statusClass,
                      String statusMapVersion, String payloadSha256, Instant checkedAt, String error) {
    }

    /** The last {@code OK} status of a serial number. */
    public record Latest(String serial, String statusCode, String statusDescription, LocalDate statusDate,
                         StatusClass statusClass, Instant checkedAt) {
    }

    /** A mark that recent findings named, with what is known about it. */
    public record MarkReport(String sourceId, String serial, String display, String catalogStatus, long matches,
                             Latest latestOk, CallResult lastResult, Instant lastCheckedAt) {
    }

    static final int MAX_ERROR_CHARS = 500;
    static final int MAX_MESSAGE_CHARS = 1000;
    static final int MAX_DESCRIPTION_CHARS = 300;

    private static final String SERIAL_SOURCE_ID = "^uspto:[0-9]{8}$";

    /** The findings of checks that are still retained and recent, grouped by the source id of the mark they name. */
    private static final String MATCHED = """
            WITH matched AS (
                SELECT sid AS source_id, count(*) AS matches, max(c.created_at) AS last_match
                FROM trademark_check_finding f
                JOIN trademark_check c ON c.id = f.check_id
                CROSS JOIN LATERAL unnest(f.source_ids) AS sid
                WHERE c.expires_at > ? AND c.created_at >= ? AND sid ~ '%s'
                GROUP BY sid)
            """.formatted(SERIAL_SOURCE_ID);

    /**
     * The marks of the served catalog by each source id they hold (an entry holds the ids of every record merged into it). One
     * pass over the version, joined to the small set of named ids, rather than one scan for each id.
     */
    private static final String ENTRY_OF_SERVED_CATALOG = """
            JOIN (SELECT sid AS source_id, x.display, x.status
                  FROM trademark_entry x CROSS JOIN LATERAL unnest(x.source_ids) AS sid
                  WHERE x.catalog_version_id = ?) e ON e.source_id = m.source_id
            """;

    /** True when the mark was asked about so recently that it is not asked again (two intervals: answered, or failed). */
    private static final String NOT_RECENTLY_CHECKED = """
            NOT EXISTS (SELECT 1 FROM tsdr_status t WHERE t.serial_number = substr(%s, 7)
                        AND ((t.call_result IN ('OK', 'NOT_FOUND') AND t.checked_at > ?)
                             OR (t.call_result NOT IN ('OK', 'NOT_FOUND') AND t.checked_at > ?)))
            """;

    private final JdbcTemplate jdbc;

    public TsdrStatusStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- runs ---

    public long startRun(Mode mode, long catalogVersionId, String catalogVersion, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO tsdr_status_run (started_at, mode, status, catalog_version_id, catalog_version)
                VALUES (?, ?, 'RUNNING', ?, ?) RETURNING id
                """, Long.class, Timestamp.from(now), mode.name(), catalogVersionId, catalogVersion);
    }

    public void finishRun(long runId, RunStatus status, int selected, int recorded, int failed, String message, Instant now) {
        jdbc.update("""
                UPDATE tsdr_status_run SET finished_at = ?, status = ?, selected = ?, recorded = ?, failed = ?, message = ?
                WHERE id = ?
                """, Timestamp.from(now), status.name(), selected, recorded, failed, bounded(message, MAX_MESSAGE_CHARS), runId);
    }

    // --- choosing marks ---

    /**
     * Marks of the served catalog that recent findings named, the most named first, that were not asked about within the
     * intervals. Never reads what a client typed.
     */
    public List<Candidate> selectMatched(long catalogVersionId, Instant now, Instant matchedFrom, Instant answeredCutoff,
                                         Instant failedCutoff, int limit) {
        String sql = MATCHED + """
                SELECT m.source_id, e.display, m.matches
                FROM matched m
                """ + ENTRY_OF_SERVED_CATALOG + """
                WHERE\s""" + NOT_RECENTLY_CHECKED.formatted("m.source_id") + """
                ORDER BY m.matches DESC, m.last_match DESC, m.source_id
                LIMIT ?
                """;
        return jdbc.query(sql, (rs, i) -> candidate(rs.getString("source_id"), rs.getString("display"), rs.getLong("matches")),
                Timestamp.from(now), Timestamp.from(matchedFrom), catalogVersionId, Timestamp.from(answeredCutoff),
                Timestamp.from(failedCutoff), limit);
    }

    /** Marks of the served catalog with a USPTO serial number, in catalog order, not asked about within the intervals. */
    public List<Candidate> selectUnmatched(long catalogVersionId, Instant answeredCutoff, Instant failedCutoff, int limit) {
        String sql = """
                SELECT e.sid AS source_id, e.display
                FROM (SELECT x.id, sid, x.display FROM trademark_entry x CROSS JOIN LATERAL unnest(x.source_ids) AS sid
                      WHERE x.catalog_version_id = ?) e
                WHERE e.sid ~ '%s' AND\s""".formatted(SERIAL_SOURCE_ID)
                + NOT_RECENTLY_CHECKED.formatted("e.sid") + """
                ORDER BY e.id, e.sid
                LIMIT ?
                """;
        return jdbc.query(sql, (rs, i) -> candidate(rs.getString("source_id"), rs.getString("display"), 0),
                catalogVersionId, Timestamp.from(answeredCutoff), Timestamp.from(failedCutoff), limit);
    }

    private static Candidate candidate(String sourceId, String display, long matches) {
        return new Candidate(sourceId, sourceId.substring("uspto:".length()), display, matches);
    }

    // --- answers ---

    public void insert(long runId, Row row) {
        jdbc.update("""
                INSERT INTO tsdr_status (run_id, serial_number, source, call_result, http_status, attempts, status_code,
                    status_description, status_date, status_class, status_map_version, payload_sha256, checked_at, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, row.serial(), row.source().name(), row.result().name(), row.httpStatus(), row.attempts(),
                row.statusCode(), bounded(row.statusDescription(), MAX_DESCRIPTION_CHARS),
                row.statusDate() == null ? null : java.sql.Date.valueOf(row.statusDate()),
                row.statusClass() == null ? null : row.statusClass().name(), row.statusMapVersion(), row.payloadSha256(),
                Timestamp.from(row.checkedAt()), bounded(row.error(), MAX_ERROR_CHARS));
    }

    /** True when this serial number already has a stored file with this SHA-256 (the same file is not read twice). */
    public boolean hasFile(String serial, String sha256) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tsdr_status WHERE serial_number = ? AND source = 'FILE' AND payload_sha256 = ?",
                Integer.class, serial, sha256);
        return count != null && count > 0;
    }

    // --- reading ---

    /** The last {@code OK} status of each of these serial numbers; a serial number never answered is absent. */
    public Map<String, Latest> latestOk(Collection<String> serials) {
        if (serials.isEmpty()) {
            return Map.of();
        }
        Map<String, Latest> latest = new LinkedHashMap<>();
        jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    SELECT DISTINCT ON (serial_number) serial_number, status_code, status_description, status_date,
                           status_class, checked_at
                    FROM tsdr_status
                    WHERE call_result = 'OK' AND serial_number = ANY (?)
                    ORDER BY serial_number, checked_at DESC, id DESC
                    """);
            statement.setArray(1, con.createArrayOf("varchar", serials.toArray(String[]::new)));
            return statement;
        }, (ResultSet rs) -> {
            Latest row = latest(rs, "checked_at");
            latest.put(row.serial(), row);
        });
        return latest;
    }

    /** Every mark of the served catalog that recent findings named, with its last OK status and its last try. */
    public List<MarkReport> matchedMarks(long catalogVersionId, Instant now, Instant matchedFrom) {
        String sql = MATCHED + """
                SELECT m.source_id, e.display, e.status AS catalog_status, m.matches,
                       ok.serial_number AS ok_serial, ok.status_code, ok.status_description, ok.status_date, ok.status_class,
                       ok.checked_at AS ok_checked_at, last.call_result AS last_result, last.checked_at AS last_checked_at
                FROM matched m
                """ + ENTRY_OF_SERVED_CATALOG + """
                LEFT JOIN LATERAL (SELECT * FROM tsdr_status t WHERE t.serial_number = substr(m.source_id, 7)
                                   AND t.call_result = 'OK' ORDER BY t.checked_at DESC, t.id DESC LIMIT 1) ok ON true
                LEFT JOIN LATERAL (SELECT call_result, checked_at FROM tsdr_status t
                                   WHERE t.serial_number = substr(m.source_id, 7)
                                   ORDER BY t.checked_at DESC, t.id DESC LIMIT 1) last ON true
                ORDER BY m.matches DESC, m.source_id
                """;
        Map<String, MarkReport> marks = new LinkedHashMap<>();
        jdbc.query(sql, (ResultSet rs) -> {
            String sourceId = rs.getString("source_id");
            Latest ok = rs.getString("ok_serial") == null ? null : new Latest(rs.getString("ok_serial"),
                    rs.getString("status_code"), rs.getString("status_description"), date(rs.getDate("status_date")),
                    StatusClass.valueOf(rs.getString("status_class")), rs.getTimestamp("ok_checked_at").toInstant());
            marks.putIfAbsent(sourceId, new MarkReport(sourceId, sourceId.substring("uspto:".length()), rs.getString("display"),
                    rs.getString("catalog_status"), rs.getLong("matches"), ok, rs.getString("last_result") == null ? null
                    : CallResult.valueOf(rs.getString("last_result")),
                    rs.getTimestamp("last_checked_at") == null ? null : rs.getTimestamp("last_checked_at").toInstant()));
        }, Timestamp.from(now), Timestamp.from(matchedFrom), catalogVersionId);
        return List.copyOf(marks.values());
    }

    private static Latest latest(ResultSet rs, String checkedColumn) throws SQLException {
        return new Latest(rs.getString("serial_number"), rs.getString("status_code"), rs.getString("status_description"),
                date(rs.getDate("status_date")), StatusClass.valueOf(rs.getString("status_class")),
                rs.getTimestamp(checkedColumn).toInstant());
    }

    private static LocalDate date(java.sql.Date date) {
        return date == null ? null : date.toLocalDate();
    }

    static String bounded(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
    }
}
