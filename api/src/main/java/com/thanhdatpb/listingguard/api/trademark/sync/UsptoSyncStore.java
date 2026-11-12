package com.thanhdatpb.listingguard.api.trademark.sync;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * The state of the {@code uspto-sync} job in the database (ADR-009, PR A2d, V32): the files it knows and its runs. A file
 * that is {@link FileStatus#STORED} is never fetched or copied again. Messages and errors are bounded and never hold a key.
 */
@Component
public class UsptoSyncStore {

    /** Keep these enums equal to the {@code CHECK} constraints of V32. */
    public enum Kind { DAILY, BACKFILE }

    public enum Origin { DOWNLOAD, OFFLINE }

    public enum FileStatus { PENDING, STORED, FAILED, IGNORED, REMOVED }

    public enum Mode { OFFLINE, DOWNLOAD, STORE }

    public enum RunStatus { RUNNING, ACTIVATED, IMPORTED, NO_CHANGE, BLOCKED, FAILED }

    /** One file the sync knows. */
    public record SyncFile(long id, Kind kind, String name, LocalDate dataDate, Origin origin, FileStatus status,
                           Long sizeBytes, String sha256, Long sourceSizeBytes, String sourceChecksum, int attempts,
                           String error, Instant storedAt) {
    }

    /** What a finished run did. */
    public record RunSummary(int filesAdded, int filesFailed, Integer inputFiles, String inputDigest, Long recordsRead,
                             Long recordsWritten, String outputSha256, Long catalogVersionId, String catalogVersion,
                             String message) {

        public static RunSummary of(int filesAdded, int filesFailed, String message) {
            return new RunSummary(filesAdded, filesFailed, null, null, null, null, null, null, null, message);
        }
    }

    /** One run. */
    public record Run(long id, Mode mode, boolean backfile, RunStatus status, Instant startedAt, Instant finishedAt,
                      String inputDigest, Long catalogVersionId, String catalogVersion, String message) {
    }

    static final int MAX_ERROR_CHARS = 500;
    static final int MAX_MESSAGE_CHARS = 1000;

    private static final RowMapper<SyncFile> FILE = (rs, i) -> new SyncFile(rs.getLong("id"),
            Kind.valueOf(rs.getString("kind")), rs.getString("file_name"), rs.getDate("data_date").toLocalDate(),
            Origin.valueOf(rs.getString("origin")), FileStatus.valueOf(rs.getString("status")),
            nullableLong(rs, "size_bytes"), trimmed(rs.getString("sha256")), nullableLong(rs, "source_size_bytes"),
            rs.getString("source_checksum"), rs.getInt("attempts"), rs.getString("error"),
            instant(rs.getTimestamp("stored_at")));

    private static final RowMapper<Run> RUN = (rs, i) -> new Run(rs.getLong("id"), Mode.valueOf(rs.getString("mode")),
            rs.getBoolean("backfile"), RunStatus.valueOf(rs.getString("status")), instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("finished_at")), trimmed(rs.getString("input_digest")),
            nullableLong(rs, "catalog_version_id"), rs.getString("catalog_version"), rs.getString("message"));

    private final JdbcTemplate jdbc;

    public UsptoSyncStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- files ---

    public List<SyncFile> files(Kind kind) {
        return jdbc.query("SELECT * FROM uspto_sync_file WHERE kind = ? ORDER BY data_date, file_name", FILE, kind.name());
    }

    public Optional<SyncFile> file(Kind kind, String name) {
        return jdbc.query("SELECT * FROM uspto_sync_file WHERE kind = ? AND file_name = ?", FILE, kind.name(), name)
                .stream().findFirst();
    }

    /**
     * Records a file the source lists; a file already known keeps its status and gets the listed size and checksum when it
     * is not stored yet.
     */
    public void listed(Kind kind, String name, LocalDate dataDate, Long sourceSize, String sourceChecksum, long runId) {
        jdbc.update("""
                INSERT INTO uspto_sync_file (kind, file_name, data_date, origin, status, source_size_bytes, source_checksum,
                    first_run_id)
                VALUES (?, ?, ?, 'DOWNLOAD', 'PENDING', ?, ?, ?)
                ON CONFLICT (kind, file_name) DO UPDATE
                    SET source_size_bytes = EXCLUDED.source_size_bytes, source_checksum = EXCLUDED.source_checksum,
                        updated_at = now()
                    WHERE uspto_sync_file.status IN ('PENDING', 'FAILED')""",
                kind.name(), name, dataDate, sourceSize, bounded(sourceChecksum, 200), runId);
    }

    /** Records a verified file in the store. */
    public void stored(Kind kind, String name, LocalDate dataDate, Origin origin, long sizeBytes, String sha256,
                       int attempts, long runId) {
        jdbc.update("""
                INSERT INTO uspto_sync_file (kind, file_name, data_date, origin, status, size_bytes, sha256, attempts,
                    stored_at, first_run_id)
                VALUES (?, ?, ?, ?, 'STORED', ?, ?, ?, now(), ?)
                ON CONFLICT (kind, file_name) DO UPDATE
                    SET origin = EXCLUDED.origin, status = 'STORED', size_bytes = EXCLUDED.size_bytes,
                        sha256 = EXCLUDED.sha256, attempts = uspto_sync_file.attempts + EXCLUDED.attempts, error = NULL,
                        stored_at = now(), updated_at = now(), data_date = EXCLUDED.data_date""",
                kind.name(), name, dataDate, origin.name(), sizeBytes, sha256, attempts, runId);
    }

    /** Records that fetching a file failed this run; it stays to be tried again. */
    public void failed(Kind kind, String name, int attempts, String error) {
        jdbc.update("""
                UPDATE uspto_sync_file SET status = 'FAILED', attempts = attempts + ?, error = ?, updated_at = now()
                WHERE kind = ? AND file_name = ? AND status IN ('PENDING', 'FAILED')""",
                attempts, bounded(error, MAX_ERROR_CHARS), kind.name(), name);
    }

    /** Marks a daily file as left out on purpose, with the operator's reason; a stored file cannot be ignored. */
    public boolean ignored(String name, LocalDate dataDate, String reason, long runId) {
        int changed = jdbc.update("""
                INSERT INTO uspto_sync_file (kind, file_name, data_date, origin, status, error, first_run_id)
                VALUES ('DAILY', ?, ?, 'OFFLINE', 'IGNORED', ?, ?)
                ON CONFLICT (kind, file_name) DO UPDATE SET status = 'IGNORED', error = EXCLUDED.error, updated_at = now()
                    WHERE uspto_sync_file.status IN ('PENDING', 'FAILED', 'IGNORED')""",
                name, dataDate, bounded("ignored: " + reason, MAX_ERROR_CHARS), runId);
        return changed == 1;
    }

    // --- runs ---

    /** Marks the runs a crash left {@code RUNNING} as failed; called while holding the sync lock. */
    public int failInterruptedRuns() {
        return jdbc.update("""
                UPDATE uspto_sync_run SET status = 'FAILED', finished_at = now(),
                    message = 'interrupted: the run ended without finishing (a crash or a stop)'
                WHERE status = 'RUNNING'""");
    }

    public long startRun(Mode mode, boolean backfile) {
        return jdbc.queryForObject("INSERT INTO uspto_sync_run (mode, backfile, status) VALUES (?, ?, 'RUNNING') RETURNING id",
                Long.class, mode.name(), backfile);
    }

    public void finishRun(long id, RunStatus status, RunSummary summary) {
        jdbc.update("""
                UPDATE uspto_sync_run SET status = ?, finished_at = now(), files_added = ?, files_failed = ?, input_files = ?,
                    input_digest = ?, records_read = ?, records_written = ?, output_sha256 = ?, catalog_version_id = ?,
                    catalog_version = ?, message = ?
                WHERE id = ?""", status.name(), summary.filesAdded(), summary.filesFailed(), summary.inputFiles(),
                summary.inputDigest(), summary.recordsRead(), summary.recordsWritten(), summary.outputSha256(),
                summary.catalogVersionId(), summary.catalogVersion(), bounded(summary.message(), MAX_MESSAGE_CHARS), id);
    }

    public Optional<Run> run(long id) {
        return jdbc.query("SELECT * FROM uspto_sync_run WHERE id = ?", RUN, id).stream().findFirst();
    }

    /** The latest run that made or reused a catalog version, still stored, from the same inputs. */
    public Optional<Run> runWithDigest(String inputDigest) {
        return jdbc.query("""
                SELECT * FROM uspto_sync_run
                WHERE input_digest = ? AND catalog_version_id IS NOT NULL AND status IN ('ACTIVATED', 'IMPORTED', 'NO_CHANGE')
                ORDER BY id DESC LIMIT 1""", RUN, inputDigest).stream().findFirst();
    }

    /**
     * The catalog versions the sync made or reused, still stored, the one a run used last first (a version reused from
     * earlier inputs keeps its old id, so the id says nothing about when it was last made).
     */
    public List<Long> catalogVersionIds() {
        return jdbc.queryForList("""
                SELECT catalog_version_id FROM uspto_sync_run WHERE catalog_version_id IS NOT NULL
                GROUP BY catalog_version_id ORDER BY max(id) DESC""", Long.class);
    }

    static String bounded(String text, int max) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(c -> out.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
        return out.length() <= max ? out.toString() : out.substring(0, max - 3) + "...";
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String trimmed(String text) {
        return text == null ? null : text.trim();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
