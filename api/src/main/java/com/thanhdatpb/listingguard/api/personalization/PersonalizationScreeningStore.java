package com.thanhdatpb.listingguard.api.personalization;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.personalization.Completeness;
import com.thanhdatpb.listingguard.personalization.ScreeningResult;
import com.thanhdatpb.listingguard.personalization.TextFinding;
import com.thanhdatpb.listingguard.personalization.TrademarkHit;
import com.thanhdatpb.listingguard.personalization.TrademarkPart;
import com.thanhdatpb.listingguard.personalization.TrademarkStatus;
import com.thanhdatpb.listingguard.personalization.config.SourceStatus;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
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
 * Reads and writes personalization screening results (ADR-016, ADR-010). Every read is scoped by the owning client, and every
 * read of a retained result excludes a row past {@code expires_at}: expiry is decided here, by the database clock, whatever
 * the cleanup job has or has not done yet.
 *
 * <p>What is stored is the result, never the text the buyer typed: the canonical input hash, the findings and their bounded
 * evidence. {@link #insert} must run inside a transaction (the caller's), because for a complete result it first deletes the
 * expired row it replaces and then inserts, and the two have to stand or fall together.
 */
@Component
public class PersonalizationScreeningStore {

    /** The partial unique index of the cache identity (V25). */
    static final String CACHE_INDEX = "uq_personalization_screening_cache";

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String TEXT = "TEXT";
    private static final String TRADEMARK = "TRADEMARK";

    /** What makes two analyses interchangeable (ADR-010): the cache identity of a personalization result. */
    public record Identity(UUID clientId, String marketplace, String packId, String inputHash, long rulesetVersionId,
                           String analysisVersion) {
    }

    private static final String SELECT = """
            SELECT c.id, c.marketplace_code, r.version AS ruleset_version, c.analysis_version, c.print_profile, c.completeness,
                   c.failed_rule_ids, c.trademark_status, c.trademark_risk_level, c.trademark_catalog_version,
                   c.trademark_ruleset_version, c.trademark_failed_rule_ids, c.disclaimer, c.created_at, c.expires_at,
                   f.position, f.source, f.rule_id, f.kind, f.severity, f.source_status, f.risk, f.match_type, f.value_index,
                   f.label, f.start_offset, f.end_offset, f.value, f.mark, f.score, f.inside_color_phrase, f.other_rule_ids,
                   f.source_ids, f.message, f.suggestion
            FROM personalization_screening c
            JOIN ruleset_version r ON r.id = c.ruleset_version_id
            LEFT JOIN personalization_screening_finding f ON f.check_id = c.id
            """;

    private static final String IDENTITY_FILTER = """
            c.client_id = ? AND c.marketplace_code = ? AND c.pack_id = ? AND c.input_hash = ?
              AND c.ruleset_version_id = ? AND c.analysis_version = ?""";

    private static final String INSERT_FINDING = """
            INSERT INTO personalization_screening_finding (check_id, position, source, rule_id, kind, severity, source_status,
                risk, match_type, value_index, label, start_offset, end_offset, value, mark, score, inside_color_phrase,
                other_rule_ids, source_ids, message, suggestion)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private final JdbcTemplate jdbc;
    private final PersonalizationRetentionProperties retention;

    public PersonalizationScreeningStore(JdbcTemplate jdbc, PersonalizationRetentionProperties retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    /**
     * The complete, unexpired result of this cache identity, for {@code cached: true}. An INCOMPLETE result is never
     * returned: it is stored so that GET can read it again, not so that it can answer another request. The result and its
     * findings come from one statement, so they are one consistent snapshot even if cleanup runs meanwhile.
     */
    public Optional<PersonalizationScreeningResult> findCached(Identity identity) {
        return select(SELECT + " WHERE " + IDENTITY_FILTER
                        + " AND c.completeness = 'COMPLETE' AND c.expires_at > now() ORDER BY f.position",
                true, identity.clientId(), identity.marketplace(), identity.packId(), identity.inputHash(),
                identity.rulesetVersionId(), identity.analysisVersion());
    }

    /** A retained result of this client, as stored ({@code cached} false). Empty also for another client's and an expired one. */
    public Optional<PersonalizationScreeningResult> find(UUID clientId, UUID id) {
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
    public UUID insert(Identity identity, ScreeningResult result) {
        if (result.completeness() == Completeness.COMPLETE) {
            jdbc.update("DELETE FROM personalization_screening c WHERE " + IDENTITY_FILTER + " AND c.expires_at <= now()",
                    identity.clientId(), identity.marketplace(), identity.packId(), identity.inputHash(),
                    identity.rulesetVersionId(), identity.analysisVersion());
        }
        UUID id = UUID.randomUUID();
        double retentionSeconds = retention.period().toMillis() / 1000.0;
        TrademarkPart trademark = result.trademark();
        jdbc.update("""
                INSERT INTO personalization_screening (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id,
                    analysis_version, print_profile, completeness, failed_rule_ids, trademark_status, trademark_risk_level,
                    trademark_catalog_version, trademark_ruleset_version, trademark_failed_rule_ids, disclaimer, created_at,
                    expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now() + make_interval(secs => ?))""",
                ps -> {
                    ps.setObject(1, id);
                    ps.setObject(2, identity.clientId());
                    ps.setString(3, identity.marketplace());
                    ps.setString(4, identity.packId());
                    ps.setString(5, identity.inputHash());
                    ps.setLong(6, identity.rulesetVersionId());
                    ps.setString(7, identity.analysisVersion());
                    ps.setString(8, result.printProfile());
                    ps.setString(9, result.completeness().name());
                    ps.setArray(10, ps.getConnection().createArrayOf("text", result.failedRuleIds().toArray()));
                    ps.setString(11, trademark.status().name());
                    ps.setString(12, trademark.riskLevel().name());
                    ps.setString(13, trademark.catalogVersion());
                    ps.setString(14, trademark.rulesetVersion());
                    ps.setArray(15, ps.getConnection().createArrayOf("text", trademark.failedRuleIds().toArray()));
                    ps.setString(16, result.disclaimer());
                    ps.setDouble(17, retentionSeconds);
                });
        List<Row> rows = new ArrayList<>();
        result.findings().forEach(finding -> rows.add(Row.of(finding)));
        trademark.findings().forEach(hit -> rows.add(Row.of(hit)));
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(INSERT_FINDING, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int position) throws SQLException {
                    rows.get(position).bind(ps, id, position);
                }

                @Override
                public int getBatchSize() {
                    return rows.size();
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

    /** One row of {@code personalization_screening_finding}: a character finding or a trademark finding. */
    private record Row(String source, String ruleId, String kind, String severity, String sourceStatus, String risk,
                       String matchType, int valueIndex, String label, int start, int end, String value, String mark,
                       Double score, boolean insideColorPhrase, List<String> otherRuleIds, List<String> sourceIds,
                       String message, String suggestion) {

        static Row of(TextFinding finding) {
            return new Row(TEXT, finding.ruleId(), finding.kind(), finding.severity().name(), finding.sourceStatus().yaml(),
                    null, null, finding.valueIndex(), finding.label(), finding.start(), finding.end(), finding.value(), null,
                    null, false, List.of(), List.of(), finding.message(), finding.suggestion());
        }

        static Row of(TrademarkHit hit) {
            return new Row(TRADEMARK, hit.ruleId(), null, null, null, hit.risk().name(), hit.matchType().name(),
                    hit.valueIndex(), hit.label(), hit.start(), hit.end(), null, hit.mark(), hit.score(),
                    hit.insideColorPhrase(), hit.otherRuleIds(), hit.sourceIds(), hit.message(), hit.suggestion());
        }

        void bind(PreparedStatement ps, UUID checkId, int position) throws SQLException {
            ps.setObject(1, checkId);
            ps.setInt(2, position);
            ps.setString(3, source);
            ps.setString(4, ruleId);
            ps.setString(5, kind);
            ps.setString(6, severity);
            ps.setString(7, sourceStatus);
            ps.setString(8, risk);
            ps.setString(9, matchType);
            ps.setInt(10, valueIndex);
            ps.setString(11, label);
            ps.setInt(12, start);
            ps.setInt(13, end);
            ps.setString(14, value);
            ps.setString(15, mark);
            ps.setObject(16, score);
            ps.setBoolean(17, insideColorPhrase);
            ps.setArray(18, ps.getConnection().createArrayOf("text", otherRuleIds.toArray()));
            ps.setArray(19, ps.getConnection().createArrayOf("text", sourceIds.toArray()));
            ps.setString(20, message);
            ps.setString(21, suggestion);
        }
    }

    private Optional<PersonalizationScreeningResult> select(String sql, boolean cached, Object... args) {
        return jdbc.query(sql, rs -> {
            PersonalizationScreeningResult.TrademarkView trademark = null;
            PersonalizationScreeningResult header = null;
            List<PersonalizationScreeningResult.CharacterFindingView> findings = new ArrayList<>();
            List<PersonalizationScreeningResult.TrademarkFindingView> hits = new ArrayList<>();
            while (rs.next()) {
                if (header == null) {
                    header = header(rs, cached);
                    trademark = trademark(rs);
                }
                String source = rs.getString("source");
                if (TEXT.equals(source)) {
                    findings.add(finding(rs));
                } else if (TRADEMARK.equals(source)) {
                    hits.add(hit(rs));
                }
            }
            if (header == null) {
                return Optional.<PersonalizationScreeningResult>empty();
            }
            PersonalizationScreeningResult.TrademarkView view = new PersonalizationScreeningResult.TrademarkView(
                    trademark.status(), trademark.riskLevel(), trademark.catalogVersion(), trademark.rulesetVersion(),
                    trademark.failedRuleIds(), List.copyOf(hits));
            return Optional.of(PersonalizationScreeningResult.of(header.checkId(), header.marketplace(),
                    header.rulesetVersion(), header.analysisVersion(), header.cached(), header.printProfile(),
                    header.completeness(), header.failedRuleIds(), header.disclaimer(), header.createdAt(),
                    header.expiresAt(), List.copyOf(findings), view));
        }, args);
    }

    private static PersonalizationScreeningResult header(ResultSet rs, boolean cached) throws SQLException {
        return PersonalizationScreeningResult.of(rs.getObject("id", UUID.class), rs.getString("marketplace_code"),
                rs.getString("ruleset_version"), rs.getString("analysis_version").trim(), cached,
                rs.getString("print_profile"), Completeness.valueOf(rs.getString("completeness")),
                strings(rs.getArray("failed_rule_ids")), rs.getString("disclaimer"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(), List.of(), null);
    }

    private static PersonalizationScreeningResult.TrademarkView trademark(ResultSet rs) throws SQLException {
        return new PersonalizationScreeningResult.TrademarkView(TrademarkStatus.valueOf(rs.getString("trademark_status")),
                RiskLevel.valueOf(rs.getString("trademark_risk_level")), rs.getString("trademark_catalog_version"),
                rs.getString("trademark_ruleset_version"), strings(rs.getArray("trademark_failed_rule_ids")), List.of());
    }

    private static PersonalizationScreeningResult.CharacterFindingView finding(ResultSet rs) throws SQLException {
        return new PersonalizationScreeningResult.CharacterFindingView(rs.getString("rule_id"), rs.getString("kind"),
                Severity.valueOf(rs.getString("severity")),
                SourceStatus.valueOf(rs.getString("source_status").toUpperCase(Locale.ROOT)), field(rs),
                rs.getString("label"), rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getString("value"),
                rs.getString("message"), rs.getString("suggestion"));
    }

    private static PersonalizationScreeningResult.TrademarkFindingView hit(ResultSet rs) throws SQLException {
        return new PersonalizationScreeningResult.TrademarkFindingView(rs.getString("rule_id"),
                RiskLevel.valueOf(rs.getString("risk")), field(rs), rs.getString("label"), rs.getInt("start_offset"),
                rs.getInt("end_offset"), MatchType.valueOf(rs.getString("match_type")), rs.getString("mark"),
                rs.getObject("score", Double.class), rs.getBoolean("inside_color_phrase"),
                strings(rs.getArray("other_rule_ids")), strings(rs.getArray("source_ids")), rs.getString("message"),
                rs.getString("suggestion"));
    }

    private static String field(ResultSet rs) throws SQLException {
        return "personalization[" + rs.getInt("value_index") + "]";
    }

    private static List<String> strings(Array array) throws SQLException {
        List<String> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            values.add((String) value);
        }
        return List.copyOf(values);
    }
}
