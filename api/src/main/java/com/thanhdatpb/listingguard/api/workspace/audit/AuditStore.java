package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.AccountRun;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.AlertRow;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.AuditView;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.NewFinding;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.Progress;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.Run;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StateCounts;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredAudit;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredFinding;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Level;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Reason;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.RunStatus;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Source;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.State;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The queue, the history and the latest results of the background audit (ADR-061, V58). Every statement that reads or writes a client's data
 * starts from {@code client_id = ?}; a listing, a run or a finding of another client is simply not found. The queue is the table
 * {@code workspace_audit_run}: a row is taken with {@code FOR UPDATE SKIP LOCKED} and kept with a lease, so a run whose worker died is taken again.
 */
@Repository
@AuditEnabled
public class AuditStore {

    /** A run that has been taken this many times and still has no end is given up: it kills its worker. */
    static final int MAX_ATTEMPTS = 5;

    private static final String RUN_COLUMNS = "id, client_id, account_id, reason, status, listing_ids, requested_by, total, done, skipped, failed, "
            + "new_risks, new_risk_listings, note_code, attempts, context_key, catalog_version, watchlist_version, watchlist_revision, "
            + "ruleset_versions, created_at, started_at, finished_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final RowMapper<Run> runMapper = (rs, n) -> {
        Array ids = rs.getArray("listing_ids");
        List<UUID> listingIds = null;
        if (ids != null) {
            listingIds = new ArrayList<>();
            for (Object id : (Object[]) ids.getArray()) {
                listingIds.add((UUID) id);
            }
        }
        return new Run(rs.getObject("id", UUID.class), rs.getObject("client_id", UUID.class), rs.getObject("account_id", UUID.class),
                Reason.valueOf(rs.getString("reason")), RunStatus.valueOf(rs.getString("status")), listingIds,
                rs.getObject("requested_by", UUID.class), rs.getInt("total"), rs.getInt("done"), rs.getInt("skipped"), rs.getInt("failed"),
                rs.getInt("new_risks"), rs.getInt("new_risk_listings"), rs.getString("note_code"), rs.getInt("attempts"),
                rs.getString("context_key"), rs.getString("catalog_version"), rs.getString("watchlist_version"),
                (Long) rs.getObject("watchlist_revision"), rs.getString("ruleset_versions"), instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("started_at", OffsetDateTime.class)), instant(rs.getObject("finished_at", OffsetDateTime.class)));
    };
    private final RowMapper<StoredAudit> auditMapper = (rs, n) -> new StoredAudit(rs.getObject("listing_id", UUID.class),
            rs.getObject("client_id", UUID.class), rs.getObject("account_id", UUID.class), rs.getObject("run_id", UUID.class),
            State.valueOf(rs.getString("state")), rs.getString("input_hash").strip(), rs.getString("analysis_version").strip(),
            rs.getString("rules_analysis"), rs.getString("trademark_analysis"), rs.getBoolean("trademark_ran"), rs.getLong("listing_version"),
            rs.getString("catalog_version"), rs.getString("watchlist_version"), rs.getInt("error_count"), rs.getInt("warning_count"),
            rs.getInt("new_count"), instant(rs.getObject("audited_at", OffsetDateTime.class)),
            instant(rs.getObject("alerted_at", OffsetDateTime.class)), instant(rs.getObject("ack_at", OffsetDateTime.class)));

    public AuditStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    // ------------------------------------------------------------------ the queue

    /**
     * Puts a run in the queue. Runs of one account that wait are merged: a request for the whole account widens a waiting run to the whole
     * account, a request for some listings adds them to a waiting run (or does nothing when that run already covers the account). A run
     * that is working is left alone: the new request waits behind it.
     *
     * @param listingIds the listings to audit, or null for every live listing of the account
     * @return the run that will do it
     */
    public UUID enqueue(UUID clientId, UUID accountId, Reason reason, Collection<UUID> listingIds, UUID requestedBy) {
        return transactions.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
            }, "workspace-audit:" + accountId);
            List<Run> waiting = jdbc.query("SELECT " + RUN_COLUMNS + " FROM workspace_audit_run WHERE client_id = ? AND account_id = ? "
                    + "AND status = 'QUEUED' ORDER BY created_at LIMIT 1", runMapper, clientId, accountId);
            if (!waiting.isEmpty()) {
                Run run = waiting.get(0);
                if (listingIds == null && run.listingIds() != null) {
                    jdbc.update("UPDATE workspace_audit_run SET listing_ids = NULL WHERE id = ? AND client_id = ?", run.id(), clientId);
                } else if (listingIds != null && run.listingIds() != null) {
                    Set<UUID> merged = new LinkedHashSet<>(run.listingIds());
                    merged.addAll(listingIds);
                    jdbc.update("UPDATE workspace_audit_run SET listing_ids = ? WHERE id = ? AND client_id = ?", (PreparedStatementSetter) ps -> {
                        ps.setArray(1, ps.getConnection().createArrayOf("uuid", merged.toArray()));
                        ps.setObject(2, run.id());
                        ps.setObject(3, clientId);
                    });
                }
                return run.id();
            }
            UUID id = UUID.randomUUID();
            jdbc.update(con -> {
                var ps = con.prepareStatement("INSERT INTO workspace_audit_run (id, client_id, account_id, reason, status, listing_ids, requested_by, "
                        + "created_at) VALUES (?, ?, ?, ?, 'QUEUED', ?, ?, ?)");
                ps.setObject(1, id);
                ps.setObject(2, clientId);
                ps.setObject(3, accountId);
                ps.setString(4, reason.name());
                ps.setArray(5, listingIds == null ? null : con.createArrayOf("uuid", new LinkedHashSet<>(listingIds).toArray()));
                ps.setObject(6, requestedBy);
                ps.setTimestamp(7, ts(clock.instant()));
                return ps;
            });
            return id;
        });
    }

    /**
     * Takes the oldest run that waits, or whose worker stopped keeping its lease, and reserves it for {@code lease}. One account has one run
     * working at a time. A run taken {@value #MAX_ATTEMPTS} times without an end is given up first.
     */
    public Optional<Run> claim(Duration lease) {
        jdbc.update("UPDATE workspace_audit_run SET status = 'FAILED', note_code = 'attempts', finished_at = now() WHERE status = 'RUNNING' "
                + "AND lease_until < now() AND attempts >= ?", MAX_ATTEMPTS);
        return jdbc.query("UPDATE workspace_audit_run r SET status = 'RUNNING', started_at = COALESCE(r.started_at, now()), "
                + "lease_until = now() + (? * interval '1 millisecond'), attempts = r.attempts + 1 "
                + "WHERE r.id = (SELECT c.id FROM workspace_audit_run c WHERE (c.status = 'QUEUED' OR (c.status = 'RUNNING' AND c.lease_until < now())) "
                + "AND NOT EXISTS (SELECT 1 FROM workspace_audit_run o WHERE o.account_id = c.account_id AND o.id <> c.id "
                + "AND o.status = 'RUNNING' AND o.lease_until >= now()) ORDER BY c.created_at FOR UPDATE SKIP LOCKED LIMIT 1) "
                + "RETURNING " + RUN_COLUMNS, runMapper, lease.toMillis()).stream().findFirst();
    }

    /** Writes the progress and keeps the lease. Returns false when the run is no longer this worker's (it was taken again after the lease ran out). */
    public boolean heartbeat(UUID clientId, UUID runId, Progress progress, Duration lease) {
        return jdbc.update("UPDATE workspace_audit_run SET total = ?, done = ?, skipped = ?, failed = ?, new_risks = ?, new_risk_listings = ?, "
                + "lease_until = now() + (? * interval '1 millisecond') WHERE id = ? AND client_id = ? AND status = 'RUNNING'",
                progress.total(), progress.done(), progress.skipped(), progress.failed(), progress.newRisks(), progress.newRiskListings(),
                lease.toMillis(), runId, clientId) == 1;
    }

    /** Ends a run with what it used. */
    public void finish(UUID clientId, UUID runId, Progress progress, String noteCode, String contextKey, String catalogVersion,
                       String watchlistVersion, Long watchlistRevision, String rulesetVersions) {
        jdbc.update("UPDATE workspace_audit_run SET status = 'DONE', total = ?, done = ?, skipped = ?, failed = ?, new_risks = ?, "
                + "new_risk_listings = ?, note_code = ?, context_key = ?, catalog_version = ?, watchlist_version = ?, watchlist_revision = ?, "
                + "ruleset_versions = ?, finished_at = now(), lease_until = NULL WHERE id = ? AND client_id = ?", progress.total(), progress.done(),
                progress.skipped(), progress.failed(), progress.newRisks(), progress.newRiskListings(), noteCode, contextKey, catalogVersion,
                watchlistVersion, watchlistRevision, rulesetVersions, runId, clientId);
    }

    public void fail(UUID clientId, UUID runId, String noteCode, String contextKey) {
        jdbc.update("UPDATE workspace_audit_run SET status = 'FAILED', note_code = ?, context_key = ?, finished_at = now(), lease_until = NULL "
                + "WHERE id = ? AND client_id = ?", noteCode, contextKey, runId, clientId);
    }

    /** Deletes the oldest finished runs of an account beyond {@code keep}. */
    public int prune(UUID clientId, UUID accountId, int keep) {
        return jdbc.update("DELETE FROM workspace_audit_run WHERE id IN (SELECT id FROM (SELECT id, row_number() OVER (ORDER BY created_at DESC) AS rn "
                + "FROM workspace_audit_run WHERE client_id = ? AND account_id = ? AND status IN ('DONE', 'FAILED')) ranked WHERE rn > ?)",
                clientId, accountId, keep);
    }

    public Optional<Run> run(UUID clientId, UUID runId) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM workspace_audit_run WHERE id = ? AND client_id = ?", runMapper, runId, clientId)
                .stream().findFirst();
    }

    /** The newest run of each seller account of the client that has one, with the account's name. */
    public List<AccountRun> latestRuns(UUID clientId) {
        return jdbc.query("SELECT DISTINCT ON (r.account_id) a.name AS account_name, a.marketplace, "
                + RUN_COLUMNS.replaceAll("(^|, )(\\w+)", "$1r.$2") + " FROM workspace_audit_run r "
                + "JOIN workspace_account a ON a.id = r.account_id AND a.client_id = r.client_id WHERE r.client_id = ? "
                + "ORDER BY r.account_id, r.created_at DESC", (rs, n) -> new AccountRun(rs.getObject("account_id", UUID.class),
                rs.getString("account_name"), rs.getString("marketplace"), runMapper.mapRow(rs, n)), clientId);
    }

    /** The history of the client's runs, newest first. */
    public List<AccountRun> history(UUID clientId, int limit) {
        return jdbc.query("SELECT a.name AS account_name, a.marketplace, " + RUN_COLUMNS.replaceAll("(^|, )(\\w+)", "$1r.$2")
                + " FROM workspace_audit_run r JOIN workspace_account a ON a.id = r.account_id AND a.client_id = r.client_id "
                + "WHERE r.client_id = ? ORDER BY r.created_at DESC LIMIT ?", (rs, n) -> new AccountRun(rs.getObject("account_id", UUID.class),
                rs.getString("account_name"), rs.getString("marketplace"), runMapper.mapRow(rs, n)), clientId, limit);
    }

    /** The newest run of an account that ended, for the comparison with the context now. */
    public Optional<Run> lastFinished(UUID clientId, UUID accountId) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM workspace_audit_run WHERE client_id = ? AND account_id = ? AND status IN ('DONE', 'FAILED') "
                + "ORDER BY created_at DESC LIMIT 1", runMapper, clientId, accountId).stream().findFirst();
    }

    public boolean hasOpenRun(UUID clientId, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM workspace_audit_run WHERE client_id = ? AND account_id = ? "
                + "AND status IN ('QUEUED', 'RUNNING'))", Boolean.class, clientId, accountId));
    }

    /** One enabled seller account that has a live listing, for the drift check and the periodic audit. */
    public record AccountRef(UUID clientId, UUID accountId, String marketplace) {
    }

    public List<AccountRef> accountsWithListings() {
        return jdbc.query("SELECT a.client_id, a.id, a.marketplace FROM workspace_account a WHERE a.enabled AND EXISTS "
                + "(SELECT 1 FROM workspace_listing l WHERE l.account_id = a.id AND l.client_id = a.client_id AND l.deleted_at IS NULL) "
                + "ORDER BY a.client_id, a.id", (rs, n) -> new AccountRef(rs.getObject("client_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getString("marketplace")));
    }

    /** The ids of the live listings of an account, in a stable order; when {@code only} is given, those of them that are in it. */
    public List<UUID> liveListingIds(UUID clientId, UUID accountId, Collection<UUID> only) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM workspace_listing WHERE client_id = ? AND account_id = ? AND deleted_at IS NULL "
                + "ORDER BY id", UUID.class, clientId, accountId);
        if (only == null) {
            return ids;
        }
        Set<UUID> wanted = new LinkedHashSet<>(only);
        return ids.stream().filter(wanted::contains).toList();
    }

    /** What a run found, from the results it stored (so a run that was taken again after a crash still counts what the first attempt found). */
    public Progress foundBy(UUID clientId, UUID runId, Progress progress) {
        long[] totals = jdbc.queryForObject("SELECT COUNT(*) FILTER (WHERE state = 'NEW_RISK' AND alerted_at IS NULL), "
                + "COALESCE(SUM(new_count) FILTER (WHERE state = 'NEW_RISK' AND alerted_at IS NULL), 0) FROM workspace_listing_audit "
                + "WHERE client_id = ? AND run_id = ?", (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, clientId, runId);
        return new Progress(progress.total(), progress.done(), progress.skipped(), progress.failed(), (int) totals[1], (int) totals[0]);
    }

    // ------------------------------------------------------------------ the latest result of a listing

    public Optional<StoredAudit> audit(UUID clientId, UUID listingId) {
        return jdbc.query("SELECT * FROM workspace_listing_audit WHERE listing_id = ? AND client_id = ?", auditMapper, listingId, clientId)
                .stream().findFirst();
    }

    public Map<UUID, StoredAudit> audits(UUID clientId, Collection<UUID> listingIds) {
        Map<UUID, StoredAudit> found = new HashMap<>();
        List<UUID> all = new ArrayList<>(listingIds);
        for (int from = 0; from < all.size(); from += 1000) {
            List<UUID> chunk = all.subList(from, Math.min(all.size(), from + 1000));
            jdbc.query("SELECT * FROM workspace_listing_audit WHERE client_id = ? AND listing_id = ANY (?)", (PreparedStatementSetter) ps -> {
                ps.setObject(1, clientId);
                ps.setArray(2, ps.getConnection().createArrayOf("uuid", chunk.toArray()));
            }, (RowCallbackHandler) rs -> {
                StoredAudit audit = auditMapper.mapRow(rs, 0);
                found.put(audit.listingId(), audit);
            });
        }
        return found;
    }

    /** What the list shows of each of these listings; a listing with no audit has no entry. */
    public Map<UUID, AuditView> views(UUID clientId, Collection<UUID> listingIds) {
        Map<UUID, AuditView> views = new LinkedHashMap<>();
        audits(clientId, listingIds).forEach((id, a) -> views.put(id, new AuditView(id, a.state(), a.errorCount(), a.warningCount(), a.newCount(),
                a.auditedAt(), a.trademarkRan(), a.catalogVersion())));
        return views;
    }

    public List<StoredFinding> findings(UUID clientId, UUID listingId) {
        return jdbc.query("SELECT * FROM workspace_listing_finding WHERE client_id = ? AND listing_id = ? ORDER BY position", (rs, n) ->
                new StoredFinding(rs.getLong("id"), rs.getInt("position"), rs.getString("pack"), rs.getString("rule_id"),
                        Level.of(rs.getString("level")).orElseThrow(), Source.valueOf(rs.getString("source")), rs.getString("field"),
                        (Integer) rs.getObject("span_start"), (Integer) rs.getObject("span_end"), rs.getString("mark"), rs.getString("serial"),
                        rs.getString("catalog_version"), rs.getObject("watchlist_entry", UUID.class), rs.getString("tsdr_url"),
                        rs.getString("message"), rs.getString("suggestion"), rs.getBoolean("is_new")), clientId, listingId);
    }

    /**
     * Stores the latest audit of a listing and replaces its findings, in one transaction, only if the listing is still at {@code listingVersion}
     * and not deleted. Returns false when the listing changed meanwhile (the caller reads it again); nothing was written then.
     */
    public boolean save(StoredAudit audit, List<NewFinding> findings) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int written = jdbc.update("INSERT INTO workspace_listing_audit (listing_id, client_id, account_id, run_id, state, input_hash, analysis_version, "
                    + "rules_analysis, trademark_analysis, trademark_ran, listing_version, catalog_version, watchlist_version, error_count, warning_count, "
                    + "new_count, audited_at, alerted_at, ack_at) SELECT l.id, l.client_id, l.account_id, ?, ?, ?, ?, ?, ?, ?, l.version, ?, ?, ?, ?, ?, ?, ?, ? "
                    + "FROM workspace_listing l WHERE l.id = ? AND l.client_id = ? AND l.version = ? AND l.deleted_at IS NULL "
                    + "ON CONFLICT (listing_id) DO UPDATE SET run_id = EXCLUDED.run_id, state = EXCLUDED.state, input_hash = EXCLUDED.input_hash, "
                    + "analysis_version = EXCLUDED.analysis_version, rules_analysis = EXCLUDED.rules_analysis, trademark_analysis = EXCLUDED.trademark_analysis, "
                    + "trademark_ran = EXCLUDED.trademark_ran, listing_version = EXCLUDED.listing_version, catalog_version = EXCLUDED.catalog_version, "
                    + "watchlist_version = EXCLUDED.watchlist_version, error_count = EXCLUDED.error_count, warning_count = EXCLUDED.warning_count, "
                    + "new_count = EXCLUDED.new_count, audited_at = EXCLUDED.audited_at, alerted_at = EXCLUDED.alerted_at, ack_at = EXCLUDED.ack_at",
                    audit.runId(), audit.state().name(), audit.inputHash(), audit.analysisVersion(), audit.rulesAnalysis(), audit.trademarkAnalysis(),
                    audit.trademarkRan(), audit.catalogVersion(), audit.watchlistVersion(), audit.errorCount(), audit.warningCount(), audit.newCount(),
                    ts(audit.auditedAt()), ts(audit.alertedAt()), ts(audit.ackAt()), audit.listingId(), audit.clientId(), audit.listingVersion());
            if (written == 0) {
                status.setRollbackOnly();
                return false;
            }
            jdbc.update("DELETE FROM workspace_listing_finding WHERE listing_id = ? AND client_id = ?", audit.listingId(), audit.clientId());
            int[] position = {0};
            jdbc.batchUpdate("INSERT INTO workspace_listing_finding (client_id, listing_id, position, pack, rule_id, level, source, field, span_start, "
                    + "span_end, mark, serial, catalog_version, watchlist_entry, tsdr_url, message, suggestion, is_new) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", findings, 200, (ps, f) -> {
                ps.setObject(1, audit.clientId());
                ps.setObject(2, audit.listingId());
                ps.setInt(3, position[0]++);
                ps.setString(4, f.pack());
                ps.setString(5, f.ruleId());
                ps.setString(6, f.level().code());
                ps.setString(7, f.source().name());
                ps.setString(8, cut(f.field(), 64));
                ps.setObject(9, f.spanStart());
                ps.setObject(10, f.spanEnd());
                ps.setString(11, cut(f.mark(), 255));
                ps.setString(12, cut(f.serial(), 64));
                ps.setString(13, cut(f.catalogVersion(), 255));
                ps.setObject(14, f.watchlistEntry());
                ps.setString(15, cut(f.tsdrUrl(), 512));
                ps.setString(16, f.message());
                ps.setString(17, f.suggestion());
                ps.setBoolean(18, f.isNew());
            });
            return true;
        }));
    }

    private static String cut(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, Math.min(max, text.codePointCount(0, text.length()))));
    }

    /** The person has seen the new risks of this listing: they stop being new, and the listing goes back to its error or warning state. */
    public boolean acknowledge(UUID clientId, UUID listingId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int changed = jdbc.update("UPDATE workspace_listing_audit SET ack_at = ?, new_count = 0, state = CASE WHEN error_count > 0 THEN 'ERROR' "
                    + "WHEN warning_count > 0 THEN 'WARNING' ELSE 'CLEAN' END WHERE listing_id = ? AND client_id = ? AND state = 'NEW_RISK'",
                    ts(clock.instant()), listingId, clientId);
            if (changed == 1) {
                jdbc.update("UPDATE workspace_listing_finding SET is_new = FALSE WHERE listing_id = ? AND client_id = ?", listingId, clientId);
            }
            return changed == 1;
        }));
    }

    // ------------------------------------------------------------------ counts for the pages

    public StateCounts counts(UUID clientId, UUID ownerId) {
        String owner = ownerId == null ? "" : " AND l.owner_account_id = ?";
        Object[] args = ownerId == null ? new Object[] {clientId} : new Object[] {clientId, ownerId};
        long[] n = new long[6];
        jdbc.query("SELECT a.state, COUNT(*) AS n FROM workspace_listing l LEFT JOIN workspace_listing_audit a ON a.listing_id = l.id "
                + "AND a.client_id = l.client_id WHERE l.client_id = ? AND l.deleted_at IS NULL" + owner + " GROUP BY a.state", rs -> {
            String state = rs.getString("state");
            int index = state == null ? 5 : switch (State.valueOf(state)) {
                case CLEAN -> 0;
                case WARNING -> 1;
                case ERROR -> 2;
                case NEW_RISK -> 3;
                case INCOMPLETE -> 4;
            };
            n[index] += rs.getLong("n");
        }, args);
        return new StateCounts(n[0], n[1], n[2], n[3], n[4], n[5]);
    }

    /** The most recent time a listing of the client was audited, or null. */
    public Instant lastAuditedAt(UUID clientId) {
        return instant(jdbc.queryForObject("SELECT max(audited_at) FROM workspace_listing_audit WHERE client_id = ?", OffsetDateTime.class, clientId));
    }

    // ------------------------------------------------------------------ alerts

    /** The new risks nobody was told about yet, one row per finding. Every row is of this client. */
    public List<AlertRow> unalerted(UUID clientId) {
        return jdbc.query("SELECT l.id AS listing_id, l.sku, a.id AS account_id, a.name AS account_name, l.owner_account_id, f.field, f.mark, "
                + "f.level, f.span_start, f.span_end FROM workspace_listing_audit au "
                + "JOIN workspace_listing l ON l.id = au.listing_id AND l.client_id = au.client_id AND l.deleted_at IS NULL "
                + "JOIN workspace_account a ON a.id = l.account_id AND a.client_id = l.client_id "
                + "JOIN workspace_listing_finding f ON f.listing_id = au.listing_id AND f.client_id = au.client_id AND f.is_new "
                + "WHERE au.client_id = ? AND au.state = 'NEW_RISK' AND au.alerted_at IS NULL ORDER BY a.name, l.sku, f.position",
                (rs, n) -> new AlertRow(rs.getObject("listing_id", UUID.class), rs.getString("sku"), rs.getObject("account_id", UUID.class),
                        rs.getString("account_name"), rs.getObject("owner_account_id", UUID.class), rs.getString("field"), rs.getString("mark"),
                        Level.of(rs.getString("level")).orElseThrow(), (Integer) rs.getObject("span_start"), (Integer) rs.getObject("span_end")),
                clientId);
    }

    /** The stored fields of these listings of the client, for the excerpts of an alert. */
    public Map<UUID, String> fieldsOf(UUID clientId, Collection<UUID> listingIds) {
        Map<UUID, String> fields = new HashMap<>();
        List<UUID> all = new ArrayList<>(listingIds);
        for (int from = 0; from < all.size(); from += 500) {
            List<UUID> chunk = all.subList(from, Math.min(all.size(), from + 500));
            jdbc.query("SELECT id, fields::text AS fields FROM workspace_listing WHERE client_id = ? AND id = ANY (?)",
                    (PreparedStatementSetter) ps -> {
                        ps.setObject(1, clientId);
                        ps.setArray(2, ps.getConnection().createArrayOf("uuid", chunk.toArray()));
                    }, (RowCallbackHandler) rs -> {
                        fields.put(rs.getObject("id", UUID.class), rs.getString("fields"));
                    });
        }
        return fields;
    }

    public void markAlerted(UUID clientId, Collection<UUID> listingIds, Instant at) {
        List<UUID> all = new ArrayList<>(listingIds);
        for (int from = 0; from < all.size(); from += 1000) {
            List<UUID> chunk = all.subList(from, Math.min(all.size(), from + 1000));
            jdbc.update(con -> {
                var ps = con.prepareStatement("UPDATE workspace_listing_audit SET alerted_at = ? WHERE client_id = ? AND listing_id = ANY (?) "
                        + "AND alerted_at IS NULL");
                ps.setTimestamp(1, ts(at));
                ps.setObject(2, clientId);
                ps.setArray(3, con.createArrayOf("uuid", chunk.toArray()));
                return ps;
            });
        }
    }

    public boolean emailEnabled(UUID clientId, UUID accountId) {
        return jdbc.query("SELECT email_enabled FROM workspace_alert_pref WHERE account_id = ? AND client_id = ?", (rs, n) -> rs.getBoolean(1),
                accountId, clientId).stream().findFirst().orElse(true);
    }

    public Set<UUID> emailDisabled(UUID clientId) {
        return new LinkedHashSet<>(jdbc.queryForList("SELECT account_id FROM workspace_alert_pref WHERE client_id = ? AND NOT email_enabled",
                UUID.class, clientId));
    }

    public void setEmailEnabled(UUID clientId, UUID accountId, boolean enabled) {
        jdbc.update("INSERT INTO workspace_alert_pref (account_id, client_id, email_enabled, updated_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (account_id) DO UPDATE SET email_enabled = EXCLUDED.email_enabled, updated_at = EXCLUDED.updated_at "
                + "WHERE workspace_alert_pref.client_id = EXCLUDED.client_id", accountId, clientId, enabled, ts(clock.instant()));
    }

    public int sentOn(UUID clientId, UUID recipientId, LocalDate day) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workspace_alert_log WHERE client_id = ? AND recipient_id = ? AND sent_on = ?", Integer.class,
                clientId, recipientId, java.sql.Date.valueOf(day));
    }

    public void logSent(UUID clientId, UUID recipientId, UUID runId, LocalDate day, int listings) {
        jdbc.update("INSERT INTO workspace_alert_log (client_id, recipient_id, run_id, sent_on, listing_count) VALUES (?, ?, ?, ?, ?)", clientId,
                recipientId, runId, java.sql.Date.valueOf(day), listings);
    }
}
