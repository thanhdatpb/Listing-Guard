package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.Evidence;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads and writes trademark check results (ADR-009, ADR-010). Every read is scoped by the owning client, and every read
 * of a retained result excludes a row past {@code expires_at}: expiry is decided here, by the database clock, whatever
 * the cleanup job has or has not done yet.
 *
 * <p>What is stored is the result, never the listing: the canonical input hash, the findings and their bounded
 * evidence. {@link #insert} must run inside a transaction (the caller's), because for a complete result it first
 * deletes the expired row it replaces and then inserts, and the two have to stand or fall together.
 */
@Component
public class TrademarkCheckStore {

    /** The partial unique index of the cache identity (V17). */
    static final String CACHE_INDEX = "uq_trademark_check_cache";

    private static final String UNIQUE_VIOLATION = "23505";

    /** What makes two analyses interchangeable (ADR-010): the cache identity of a trademark result. */
    public record Identity(UUID clientId, String marketplace, String packId, String inputHash, long rulesetVersionId,
                           String analysisVersion) {
    }

    private static final String SELECT = """
            SELECT c.id, c.marketplace_code, r.version AS ruleset_version, v.version AS catalog_version,
                   c.analysis_version, c.completeness, c.risk_level, c.failed_rule_ids, c.disclaimer, c.created_at,
                   c.expires_at,
                   f.rule_id, f.risk, f.field, f.span_start, f.span_end, f.snippet, f.matched_mark, f.match_type,
                   f.score, f.inside_color_phrase, f.other_rule_ids, f.source_ids, f.message, f.suggestion
            FROM trademark_check c
            JOIN ruleset_version r ON r.id = c.ruleset_version_id
            JOIN trademark_catalog_version v ON v.id = c.catalog_version_id
            LEFT JOIN trademark_check_finding f ON f.check_id = c.id
            """;

    private static final String IDENTITY_FILTER = """
            c.client_id = ? AND c.marketplace_code = ? AND c.pack_id = ? AND c.input_hash = ?
              AND c.ruleset_version_id = ? AND c.analysis_version = ?""";

    private static final String INSERT_FINDING = """
            INSERT INTO trademark_check_finding (check_id, position, rule_id, risk, field, span_start, span_end, snippet,
                matched_mark, match_type, score, inside_color_phrase, other_rule_ids, source_ids, message, suggestion)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private final JdbcTemplate jdbc;
    private final TrademarkRetentionProperties retention;

    public TrademarkCheckStore(JdbcTemplate jdbc, TrademarkRetentionProperties retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    /**
     * The complete, unexpired result of this cache identity, for {@code cached: true}. An INCOMPLETE result is never
     * returned: it is stored so that GET can read it again, not so that it can answer another request. The result and
     * its findings come from one statement, so they are one consistent snapshot even if cleanup runs meanwhile.
     */
    public Optional<TrademarkCheckResult> findCached(Identity identity) {
        return select(SELECT + " WHERE " + IDENTITY_FILTER
                        + " AND c.completeness = 'COMPLETE' AND c.expires_at > now() ORDER BY f.position",
                true, identity.clientId(), identity.marketplace(), identity.packId(), identity.inputHash(),
                identity.rulesetVersionId(), identity.analysisVersion());
    }

    /** A retained result of this client, as stored ({@code cached} false). Empty also for another client's and an expired one. */
    public Optional<TrademarkCheckResult> find(UUID clientId, UUID id) {
        return select(SELECT + " WHERE c.id = ? AND c.client_id = ? AND c.expires_at > now() ORDER BY f.position",
                false, id, clientId);
    }

    /**
     * Stores the result. For a complete result the expired row of the same cache identity, if cleanup has not removed
     * it yet, is deleted first (its findings go with it) so that the unique index does not keep the new result out.
     * Two requests replacing the same expired row both reach the insert; the second one fails with a unique violation
     * on {@link #CACHE_INDEX}, which {@link #isCacheIdentityViolation} recognizes.
     *
     * @return the id of the new check
     */
    public UUID insert(Identity identity, long catalogVersionId, TrademarkResult result) {
        if (result.completeness() == Completeness.COMPLETE) {
            jdbc.update("DELETE FROM trademark_check c WHERE " + IDENTITY_FILTER + " AND c.expires_at <= now()",
                    identity.clientId(), identity.marketplace(), identity.packId(), identity.inputHash(),
                    identity.rulesetVersionId(), identity.analysisVersion());
        }
        UUID id = UUID.randomUUID();
        double retentionSeconds = retention.period().toMillis() / 1000.0;
        jdbc.update("""
                INSERT INTO trademark_check (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id,
                    catalog_version_id, analysis_version, completeness, risk_level, failed_rule_ids, disclaimer,
                    created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now() + make_interval(secs => ?))""",
                ps -> {
                    ps.setObject(1, id);
                    ps.setObject(2, identity.clientId());
                    ps.setString(3, identity.marketplace());
                    ps.setString(4, identity.packId());
                    ps.setString(5, identity.inputHash());
                    ps.setLong(6, identity.rulesetVersionId());
                    ps.setLong(7, catalogVersionId);
                    ps.setString(8, identity.analysisVersion());
                    ps.setString(9, result.completeness().name());
                    ps.setString(10, result.riskLevel().name());
                    ps.setArray(11, ps.getConnection().createArrayOf("text", result.failedRuleIds().toArray()));
                    ps.setString(12, result.disclaimer());
                    ps.setDouble(13, retentionSeconds);
                });
        List<TrademarkFinding> findings = result.findings();
        if (!findings.isEmpty()) {
            jdbc.batchUpdate(INSERT_FINDING, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int position) throws SQLException {
                    TrademarkFinding finding = findings.get(position);
                    Evidence evidence = finding.evidence();
                    ps.setObject(1, id);
                    ps.setInt(2, position);
                    ps.setString(3, finding.ruleId());
                    ps.setString(4, finding.risk().name());
                    ps.setString(5, evidence.field());
                    ps.setInt(6, evidence.start());
                    ps.setInt(7, evidence.end());
                    ps.setString(8, evidence.snippet());
                    ps.setString(9, evidence.matchedMark());
                    ps.setString(10, evidence.matchType().name());
                    if (evidence.score() == null) {
                        ps.setNull(11, Types.DOUBLE);
                    } else {
                        ps.setDouble(11, evidence.score());
                    }
                    ps.setBoolean(12, evidence.insideColorPhrase());
                    ps.setArray(13, ps.getConnection().createArrayOf("text", evidence.otherRuleIds().toArray()));
                    ps.setArray(14, ps.getConnection().createArrayOf("text", evidence.sourceIds().toArray()));
                    ps.setString(15, finding.message());
                    ps.setString(16, finding.suggestion());
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
     * Whether the failure is the unique violation of the cache identity: another request stored the same complete
     * result first. Any other integrity failure (a foreign key, a check constraint, another unique index) is not a cache
     * hit and must be reported as it is.
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

    private Optional<TrademarkCheckResult> select(String sql, boolean cached, Object... args) {
        return jdbc.query(sql, rs -> {
            TrademarkCheckResult result = null;
            List<TrademarkCheckResult.FindingView> findings = new ArrayList<>();
            while (rs.next()) {
                if (result == null) {
                    result = header(rs, cached);
                }
                if (rs.getString("rule_id") != null) {
                    findings.add(finding(rs));
                }
            }
            return result == null ? Optional.<TrademarkCheckResult>empty() : Optional.of(withFindings(result, findings));
        }, args);
    }

    private static TrademarkCheckResult header(ResultSet rs, boolean cached) throws SQLException {
        return new TrademarkCheckResult(rs.getObject("id", UUID.class), rs.getString("marketplace_code"),
                rs.getString("ruleset_version"), rs.getString("catalog_version"), rs.getString("analysis_version").trim(),
                cached, Completeness.valueOf(rs.getString("completeness")), RiskLevel.valueOf(rs.getString("risk_level")),
                strings(rs.getArray("failed_rule_ids")), rs.getString("disclaimer"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(), List.of());
    }

    private static TrademarkCheckResult withFindings(TrademarkCheckResult result,
                                                     List<TrademarkCheckResult.FindingView> findings) {
        return new TrademarkCheckResult(result.checkId(), result.marketplace(), result.rulesetVersion(),
                result.catalogVersion(), result.analysisVersion(), result.cached(), result.completeness(),
                result.riskLevel(), result.failedRuleIds(), result.disclaimer(), result.createdAt(), result.expiresAt(),
                List.copyOf(findings));
    }

    private static TrademarkCheckResult.FindingView finding(ResultSet rs) throws SQLException {
        double score = rs.getDouble("score");
        Double nullableScore = rs.wasNull() ? null : score;
        return new TrademarkCheckResult.FindingView(rs.getString("rule_id"), RiskLevel.valueOf(rs.getString("risk")),
                rs.getString("field"), rs.getInt("span_start"), rs.getInt("span_end"), rs.getString("snippet"),
                rs.getString("matched_mark"), MatchType.valueOf(rs.getString("match_type")), nullableScore,
                rs.getBoolean("inside_color_phrase"), strings(rs.getArray("other_rule_ids")),
                strings(rs.getArray("source_ids")), rs.getString("message"), rs.getString("suggestion"));
    }

    private static List<String> strings(Array array) throws SQLException {
        List<String> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            values.add((String) value);
        }
        return List.copyOf(values);
    }
}
