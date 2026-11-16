package com.thanhdatpb.listingguard.api.console.reaudit;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The trace of the runs of the periodic re-audit (V40, ADR-035). It reads and writes counts, hashes and SKUs, never a listing's text.
 * Every query names the client.
 */
@Repository
public class ReauditStore {

    public static final String CHECKED = "CHECKED";
    public static final String NOT_CHECKED = "NOT_CHECKED";
    public static final String UNREADABLE = "UNREADABLE";

    /**
     * One run. {@code account} is the label of the seller account the seller typed ({@code ""} when none), not a console account.
     *
     * @param catalogVersion   null when no catalog was served
     * @param watchlistVersion null when the client's watchlist had no enabled entry
     */
    public record Run(UUID id, UUID clientId, UUID accountId, String marketplace, String account, String adapter,
                      Instant startedAt, String catalogVersion, Long watchlistRevision, String watchlistVersion, int rowsTotal,
                      int rowsChecked, int findings, int findingsNew, UUID previousRunId) {
    }

    /** One listing row of a run; {@code rowHash} is null for {@link #UNREADABLE}, {@code ordinal} 0 for a row with no SKU. */
    public record Row(int position, String sku, int ordinal, String rowHash, String status, int findings) {
    }

    /** The latest run of a scope that was not made with the catalog now served, for the reminder on the home page. */
    public record Stale(String marketplace, String account, Instant startedAt, String catalogVersion) {
    }

    /** What the health report counts (ADR-018, ADR-035): runs started in a period and what they found. */
    public record Totals(long runs, long clients, long rowsChecked, long findings, long findingsNew) {
    }

    private static final RowMapper<Run> RUN = (rs, i) -> new Run(rs.getObject("id", UUID.class),
            rs.getObject("client_id", UUID.class), rs.getObject("account_id", UUID.class), rs.getString("marketplace"),
            rs.getString("account_label"), rs.getString("adapter_id"), rs.getTimestamp("started_at").toInstant(),
            rs.getString("catalog_version"), longOrNull(rs.getObject("watchlist_revision")),
            trimmed(rs.getString("watchlist_version")), rs.getInt("rows_total"), rs.getInt("rows_checked"),
            rs.getInt("findings"), rs.getInt("findings_new"), rs.getObject("previous_run_id", UUID.class));

    private static final RowMapper<Row> ROW = (rs, i) -> new Row(rs.getInt("position"), rs.getString("sku"), rs.getInt("ordinal"),
            trimmed(rs.getString("row_hash")), rs.getString("status"), rs.getInt("findings"));

    private static final String RUN_COLUMNS = """
            id, client_id, account_id, marketplace, account_label, adapter_id, started_at, catalog_version, watchlist_revision,
            watchlist_version, rows_total, rows_checked, findings, findings_new, previous_run_id""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ReauditProperties properties;

    public ReauditStore(JdbcTemplate jdbc, TransactionTemplate transactions, ReauditProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
    }

    /** The latest run of the scope, or empty when it was never audited. */
    public Optional<Run> latest(UUID clientId, String marketplace, String account) {
        return jdbc.query("SELECT " + RUN_COLUMNS + """
                 FROM reaudit_run WHERE client_id = ? AND marketplace = ? AND account_label = ?
                ORDER BY started_at DESC, id LIMIT 1""", RUN, clientId, marketplace, account).stream().findFirst();
    }

    /** The rows of a run, in the order of the file. */
    public List<Row> rows(UUID runId) {
        return jdbc.query("""
                SELECT position, sku, ordinal, row_hash, status, findings FROM reaudit_run_row WHERE run_id = ?
                ORDER BY position""", ROW, runId);
    }

    /** The newest runs of the client, any scope, newest first. */
    public List<Run> recent(UUID clientId, int limit) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM reaudit_run WHERE client_id = ? ORDER BY started_at DESC, id LIMIT ?",
                RUN, clientId, limit);
    }

    /** The labels the client used, most recently used first, without the empty one. */
    public List<String> accountLabels(UUID clientId, int limit) {
        return jdbc.queryForList("""
                SELECT account_label FROM reaudit_run WHERE client_id = ? AND account_label <> ''
                GROUP BY account_label ORDER BY max(started_at) DESC, account_label LIMIT ?""", String.class, clientId, limit);
    }

    /**
     * Stores a run with its rows and deletes the oldest runs of its scope beyond {@code keep-runs}, in one transaction. The run
     * before it is never deleted by this call (it is among the newest).
     */
    public void save(Run run, List<Row> rows) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO reaudit_run (id, client_id, account_id, marketplace, account_label, adapter_id, started_at,
                        catalog_version, watchlist_revision, watchlist_version, rows_total, rows_checked, findings, findings_new,
                        previous_run_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", run.id(), run.clientId(), run.accountId(),
                    run.marketplace(), run.account(), run.adapter(), Timestamp.from(run.startedAt()), run.catalogVersion(),
                    run.watchlistRevision(), run.watchlistVersion(), run.rowsTotal(), run.rowsChecked(), run.findings(),
                    run.findingsNew(), run.previousRunId());
            jdbc.batchUpdate("""
                    INSERT INTO reaudit_run_row (run_id, position, sku, ordinal, row_hash, status, findings)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Row row = rows.get(i);
                    ps.setObject(1, run.id());
                    ps.setInt(2, row.position());
                    ps.setString(3, row.sku());
                    ps.setInt(4, row.ordinal());
                    ps.setString(5, row.rowHash());
                    ps.setString(6, row.status());
                    ps.setInt(7, row.findings());
                }

                @Override
                public int getBatchSize() {
                    return rows.size();
                }
            });
            jdbc.update("""
                    DELETE FROM reaudit_run WHERE id IN (
                        SELECT id FROM reaudit_run WHERE client_id = ? AND marketplace = ? AND account_label = ?
                        ORDER BY started_at DESC, id OFFSET ?)""", run.clientId(), run.marketplace(), run.account(),
                    properties.keepRuns());
        });
    }

    /**
     * The scopes of the client whose latest run was made with another catalog than {@code servedCatalog}, oldest audit first. A
     * run made with no catalog counts as another one.
     */
    public List<Stale> stale(UUID clientId, String servedCatalog) {
        return jdbc.query("""
                SELECT marketplace, account_label, started_at, catalog_version FROM (
                    SELECT DISTINCT ON (marketplace, account_label) marketplace, account_label, started_at, catalog_version
                    FROM reaudit_run WHERE client_id = ?
                    ORDER BY marketplace, account_label, started_at DESC, id) latest
                WHERE catalog_version IS DISTINCT FROM ? ORDER BY started_at, marketplace, account_label""",
                (rs, i) -> new Stale(rs.getString("marketplace"), rs.getString("account_label"),
                        rs.getTimestamp("started_at").toInstant(), rs.getString("catalog_version")), clientId, servedCatalog);
    }

    /** The runs started in {@code [from, to)}, of one client or (null) of all, of one marketplace or (null) of all. */
    public Totals totals(UUID clientId, String marketplace, Instant from, Instant to) {
        String client = clientId == null ? null : clientId.toString();
        return jdbc.queryForObject("""
                SELECT count(*) AS runs, count(DISTINCT client_id) AS clients, coalesce(sum(rows_checked), 0) AS rows_checked,
                       coalesce(sum(findings), 0) AS findings, coalesce(sum(findings_new), 0) AS findings_new
                FROM reaudit_run WHERE started_at >= ? AND started_at < ? AND (?::uuid IS NULL OR client_id = ?::uuid)
                  AND (?::text IS NULL OR marketplace = ?::text)""",
                (rs, i) -> new Totals(rs.getLong("runs"), rs.getLong("clients"), rs.getLong("rows_checked"),
                        rs.getLong("findings"), rs.getLong("findings_new")),
                Timestamp.from(from), Timestamp.from(to), client, client, marketplace, marketplace);
    }

    /**
     * How many scopes (of one client or all, of one marketplace or all) have a latest run made with another catalog than
     * {@code servedCatalog}. The caller passes only a catalog that is served.
     */
    public long staleScopes(UUID clientId, String marketplace, String servedCatalog) {
        String client = clientId == null ? null : clientId.toString();
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT DISTINCT ON (client_id, marketplace, account_label) catalog_version
                    FROM reaudit_run WHERE (?::uuid IS NULL OR client_id = ?::uuid) AND (?::text IS NULL OR marketplace = ?::text)
                    ORDER BY client_id, marketplace, account_label, started_at DESC, id) latest
                WHERE catalog_version IS DISTINCT FROM ?""", Long.class, client, client, marketplace, marketplace, servedCatalog);
        return count == null ? 0 : count;
    }

    private static Long longOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
