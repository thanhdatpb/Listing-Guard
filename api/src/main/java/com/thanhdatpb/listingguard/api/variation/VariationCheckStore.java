package com.thanhdatpb.listingguard.api.variation;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.variation.Completeness;
import com.thanhdatpb.listingguard.variation.VariationFinding;
import com.thanhdatpb.listingguard.variation.VariationResult;
import com.thanhdatpb.listingguard.variation.config.SourceStatus;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads and writes variation check results (ADR-015, ADR-010). Every read is scoped by the owning client, and every read of a
 * retained result excludes a row past {@code expires_at}: expiry is decided here, by the database clock, whatever the
 * cleanup job has or has not done yet.
 *
 * <p>What is stored is the result, never the family: the canonical input hash, the findings and their bounded evidence.
 * {@link #insert} must run inside a transaction (the caller's), because for a complete result it first deletes the expired
 * row it replaces and then inserts, and the two have to stand or fall together.
 */
@Component
public class VariationCheckStore {

    /** The partial unique index of the cache identity (V23). */
    static final String CACHE_INDEX = "uq_variation_check_cache";

    private static final String UNIQUE_VIOLATION = "23505";

    /** What makes two analyses interchangeable (ADR-010): the cache identity of a variation result. */
    public record Identity(UUID clientId, String marketplace, String packId, String inputHash, long rulesetVersionId,
                           String analysisVersion) {
    }

    private static final String SELECT = """
            SELECT c.id, c.marketplace_code, r.version AS ruleset_version, c.analysis_version, c.completeness,
                   c.failed_rule_ids, c.disclaimer, c.created_at, c.expires_at,
                   f.rule_id, f.kind, f.severity, f.source_status, f.field, f.value, f.related_fields, f.message, f.suggestion
            FROM variation_check c
            JOIN ruleset_version r ON r.id = c.ruleset_version_id
            LEFT JOIN variation_check_finding f ON f.check_id = c.id
            """;

    private static final String IDENTITY_FILTER = """
            c.client_id = ? AND c.marketplace_code = ? AND c.pack_id = ? AND c.input_hash = ?
              AND c.ruleset_version_id = ? AND c.analysis_version = ?""";

    private static final String INSERT_FINDING = """
            INSERT INTO variation_check_finding (check_id, position, rule_id, kind, severity, source_status, field, value,
                related_fields, message, suggestion)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private final JdbcTemplate jdbc;
    private final VariationRetentionProperties retention;

    public VariationCheckStore(JdbcTemplate jdbc, VariationRetentionProperties retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    /**
     * The complete, unexpired result of this cache identity, for {@code cached: true}. An INCOMPLETE result is never
     * returned: it is stored so that GET can read it again, not so that it can answer another request. The result and its
     * findings come from one statement, so they are one consistent snapshot even if cleanup runs meanwhile.
     */
    public Optional<VariationCheckResult> findCached(Identity identity) {
        return select(SELECT + " WHERE " + IDENTITY_FILTER
                        + " AND c.completeness = 'COMPLETE' AND c.expires_at > now() ORDER BY f.position",
                true, identity.clientId(), identity.marketplace(), identity.packId(), identity.inputHash(),
                identity.rulesetVersionId(), identity.analysisVersion());
    }

    /** A retained result of this client, as stored ({@code cached} false). Empty also for another client's and an expired one. */
    public Optional<VariationCheckResult> find(UUID clientId, UUID id) {
        return select(SELECT + " WHERE c.id = ? AND c.client_id = ? AND c.expires_at > now() ORDER BY f.position",
                false, id, clientId);
    }

    /**
     * Stores the result. For a complete result the expired row of the same cache identity, if cleanup has not removed it
     * yet, is deleted first (its findings go with it) so that the unique index does not keep the new result out. Two
     * requests replacing the same expired row both reach the insert; the second one fails with a unique violation on
     * {@link #CACHE_INDEX}, which {@link #isCacheIdentityViolation} recognizes.
     *
     * @return the id of the new check
     */
    public UUID insert(Identity identity, VariationResult result) {
        if (result.completeness() == Completeness.COMPLETE) {
            jdbc.update("DELETE FROM variation_check c WHERE " + IDENTITY_FILTER + " AND c.expires_at <= now()",
                    identity.clientId(), identity.marketplace(), identity.packId(), identity.inputHash(),
                    identity.rulesetVersionId(), identity.analysisVersion());
        }
        UUID id = UUID.randomUUID();
        double retentionSeconds = retention.period().toMillis() / 1000.0;
        jdbc.update("""
                INSERT INTO variation_check (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id,
                    analysis_version, completeness, failed_rule_ids, disclaimer, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now() + make_interval(secs => ?))""",
                ps -> {
                    ps.setObject(1, id);
                    ps.setObject(2, identity.clientId());
                    ps.setString(3, identity.marketplace());
                    ps.setString(4, identity.packId());
                    ps.setString(5, identity.inputHash());
                    ps.setLong(6, identity.rulesetVersionId());
                    ps.setString(7, identity.analysisVersion());
                    ps.setString(8, result.completeness().name());
                    ps.setArray(9, ps.getConnection().createArrayOf("text", result.failedRuleIds().toArray()));
                    ps.setString(10, result.disclaimer());
                    ps.setDouble(11, retentionSeconds);
                });
        List<VariationFinding> findings = result.findings();
        if (!findings.isEmpty()) {
            jdbc.batchUpdate(INSERT_FINDING, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int position) throws SQLException {
                    VariationFinding finding = findings.get(position);
                    ps.setObject(1, id);
                    ps.setInt(2, position);
                    ps.setString(3, finding.ruleId());
                    ps.setString(4, finding.kind());
                    ps.setString(5, finding.severity().name());
                    ps.setString(6, finding.sourceStatus().yaml());
                    ps.setString(7, finding.field());
                    ps.setString(8, finding.value());
                    ps.setArray(9, ps.getConnection().createArrayOf("text", finding.relatedFields().toArray()));
                    ps.setString(10, finding.message());
                    ps.setString(11, finding.suggestion());
                }

                @Override
                public int getBatchSize() {
                    return findings.size();
                }
            });
        }
        return id;
    }

    /**
     * Whether the failure is the unique violation of the cache identity: another request stored the same complete result
     * first. Any other integrity failure (a foreign key, a check constraint, another unique index) is not a cache hit and
     * must be reported as it is.
     */
    public static boolean isCacheIdentityViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains("\"" + CACHE_INDEX + "\"")) {
                return true;
            }
        }
        return false;
    }

    private Optional<VariationCheckResult> select(String sql, boolean cached, Object... args) {
        return jdbc.query(sql, rs -> {
            VariationCheckResult result = null;
            List<VariationCheckResult.FindingView> findings = new ArrayList<>();
            while (rs.next()) {
                if (result == null) {
                    result = header(rs, cached);
                }
                if (rs.getString("rule_id") != null) {
                    findings.add(finding(rs));
                }
            }
            return result == null ? Optional.<VariationCheckResult>empty()
                    : Optional.of(VariationCheckResult.of(result.checkId(), result.marketplace(), result.rulesetVersion(),
                            result.analysisVersion(), result.cached(), result.completeness(), result.failedRuleIds(),
                            result.disclaimer(), result.createdAt(), result.expiresAt(), List.copyOf(findings)));
        }, args);
    }

    private static VariationCheckResult header(ResultSet rs, boolean cached) throws SQLException {
        return VariationCheckResult.of(rs.getObject("id", UUID.class), rs.getString("marketplace_code"),
                rs.getString("ruleset_version"), rs.getString("analysis_version").trim(), cached,
                Completeness.valueOf(rs.getString("completeness")), strings(rs.getArray("failed_rule_ids")),
                rs.getString("disclaimer"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(), List.of());
    }

    private static VariationCheckResult.FindingView finding(ResultSet rs) throws SQLException {
        return new VariationCheckResult.FindingView(rs.getString("rule_id"), rs.getString("kind"),
                Severity.valueOf(rs.getString("severity")),
                SourceStatus.valueOf(rs.getString("source_status").toUpperCase(Locale.ROOT)), rs.getString("field"),
                rs.getString("value"), strings(rs.getArray("related_fields")), rs.getString("message"),
                rs.getString("suggestion"));
    }

    private static List<String> strings(Array array) throws SQLException {
        List<String> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            values.add((String) value);
        }
        return List.copyOf(values);
    }
}
