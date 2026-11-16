package com.thanhdatpb.listingguard.api.feedback;

import com.thanhdatpb.listingguard.api.plan.Pack;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Every SQL statement that counts verdicts (ADR-034), shared by the statistics page and endpoint, the console home page and the
 * health report (ADR-018), so they cannot count one thing two ways. All of them are aggregates: no verdict or finding row is
 * loaded, and **{@code note} is never selected**, nor any column of a finding that holds text of the listing.
 *
 * <p>Every statement starts with {@code client_id = ?} (ADR-007). Verdicts are counted by {@code updated_at} (when the current
 * verdict was given), findings by the creation of their result; the retained packs only while {@code expires_at} is after
 * {@link Scope#now()}, as the report does (ADR-018).
 */
@Component
public class FeedbackStatsQueries {

    /** What every statement filters by. {@code marketplace} is null for all marketplaces. */
    public record Scope(UUID clientId, Instant from, Instant toExclusive, String marketplace, Instant now) {
    }

    public record VerdictRow(String verdict, long count) {
    }

    /** Verdicts of one rule; {@code source} as stored. */
    public record RuleVerdictRow(String pack, String ruleId, String source, long correct, long falsePositive, long notUseful) {

        public long total() {
            return correct + falsePositive + notUseful;
        }
    }

    public record RuleFindingRow(String pack, String ruleId, String source, long findings) {
    }

    /** {@code distinct} is the number of rules with a false positive before the list was cut. */
    public record FalsePositiveRow(String pack, String ruleId, long falsePositives, long verdicts, long distinct) {
    }

    /**
     * Verdicts of one reviewer. {@code display} is the account's e-mail or the key's label (or its public key id); it is for the
     * console of the same client only and never leaves through the API.
     */
    public record ReviewerRow(String kind, String id, String display, long correct, long falsePositive, long notUseful) {

        public long total() {
            return correct + falsePositive + notUseful;
        }
    }

    public record SourceHoldRow(String source, long checks) {
    }

    private static final String SOURCE_OF_TRADEMARK = "CASE WHEN f.matched_mark IS NULL THEN 'RULE' ELSE 'CATALOG' END";

    private static final String VERDICT_COUNTS = "count(*) FILTER (WHERE v.verdict = 'CORRECT') AS correct, "
            + "count(*) FILTER (WHERE v.verdict = 'FALSE_POSITIVE') AS false_positive, "
            + "count(*) FILTER (WHERE v.verdict = 'NOT_USEFUL') AS not_useful";

    private final JdbcTemplate jdbc;

    public FeedbackStatsQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- verdicts

    public List<VerdictRow> verdicts(Scope scope) {
        Clause where = verdictClause(scope);
        return query("SELECT v.verdict, count(*) AS n FROM finding_verdict v WHERE " + where.sql() + " GROUP BY v.verdict ORDER BY 1",
                where.args(), (rs, i) -> new VerdictRow(rs.getString("verdict"), rs.getLong("n")));
    }

    public List<RuleVerdictRow> verdictsByRule(Scope scope) {
        Clause where = verdictClause(scope);
        return query("SELECT v.pack_id, v.rule_id, v.source, " + VERDICT_COUNTS + " FROM finding_verdict v WHERE " + where.sql()
                        + " GROUP BY v.pack_id, v.rule_id, v.source", where.args(),
                (rs, i) -> new RuleVerdictRow(rs.getString("pack_id"), rs.getString("rule_id"), rs.getString("source"),
                        rs.getLong("correct"), rs.getLong("false_positive"), rs.getLong("not_useful")));
    }

    /**
     * The rules with at least one {@code FALSE_POSITIVE}, most first, then most verdicts, then pack and rule id by code point
     * ({@code COLLATE "C"}, as the report ranks), so a tie never changes between two calls.
     */
    public List<FalsePositiveRow> topFalsePositives(Scope scope, int limit) {
        Clause where = verdictClause(scope);
        List<Object> args = new ArrayList<>(where.args());
        args.add(limit);
        return query("""
                SELECT v.pack_id, v.rule_id, count(*) FILTER (WHERE v.verdict = 'FALSE_POSITIVE') AS false_positives,
                       count(*) AS verdicts, count(*) OVER () AS distinct_total
                FROM finding_verdict v WHERE %s
                GROUP BY v.pack_id, v.rule_id
                HAVING count(*) FILTER (WHERE v.verdict = 'FALSE_POSITIVE') > 0
                ORDER BY false_positives DESC, verdicts DESC, v.pack_id COLLATE "C", v.rule_id COLLATE "C"
                LIMIT ?""".formatted(where.sql()), args,
                (rs, i) -> new FalsePositiveRow(rs.getString("pack_id"), rs.getString("rule_id"), rs.getLong("false_positives"),
                        rs.getLong("verdicts"), rs.getLong("distinct_total")));
    }

    /** One row per reviewer, most verdicts first, then the reviewer's id by code point. */
    public List<ReviewerRow> verdictsByReviewer(Scope scope) {
        Clause where = verdictClause(scope);
        return query("""
                SELECT CASE WHEN v.account_id IS NOT NULL THEN 'ACCOUNT' ELSE 'API_KEY' END AS kind,
                       COALESCE(v.account_id::text, k.key_id) AS reviewer_id,
                       COALESCE(a.email, NULLIF(k.label, ''), k.key_id) AS display, %s
                FROM finding_verdict v
                LEFT JOIN account a ON a.id = v.account_id AND a.client_id = v.client_id
                LEFT JOIN api_key k ON k.id = v.api_key_id AND k.client_id = v.client_id
                WHERE %s
                GROUP BY 1, 2, 3
                ORDER BY count(*) DESC, COALESCE(v.account_id::text, k.key_id) COLLATE "C"
                """.formatted(VERDICT_COUNTS, where.sql()), where.args(),
                (rs, i) -> new ReviewerRow(rs.getString("kind"), rs.getString("reviewer_id"), rs.getString("display"),
                        rs.getLong("correct"), rs.getLong("false_positive"), rs.getLong("not_useful")));
    }

    // ---- findings, the denominator a manager compares the verdicts with

    /** Findings per rule and source of the results created in the period, the three packs that store findings. */
    public List<RuleFindingRow> findingsByRule(Scope scope) {
        List<RuleFindingRow> rows = new ArrayList<>();
        Clause listing = resultClause(scope, false, "c.pack_id = ?", Pack.LISTING_RULES.id());
        rows.addAll(query("SELECT f.rule_id, count(*) AS n FROM listing_check c JOIN finding f ON f.check_id = c.id WHERE "
                        + listing.sql() + " AND f.rule_id IS NOT NULL GROUP BY f.rule_id", listing.args(),
                (rs, i) -> new RuleFindingRow(Pack.LISTING_RULES.id(), rs.getString("rule_id"), FeedbackSource.RULE.name(),
                        rs.getLong("n"))));
        Clause trademark = resultClause(scope, true, null);
        rows.addAll(query("SELECT f.rule_id, " + SOURCE_OF_TRADEMARK + " AS source, count(*) AS n FROM trademark_check c "
                        + "JOIN trademark_check_finding f ON f.check_id = c.id WHERE " + trademark.sql() + " GROUP BY 1, 2",
                trademark.args(), (rs, i) -> new RuleFindingRow(Pack.TRADEMARK_RISK.id(), rs.getString("rule_id"),
                        rs.getString("source"), rs.getLong("n"))));
        Clause variation = resultClause(scope, true, null);
        rows.addAll(query("SELECT f.rule_id, count(*) AS n FROM variation_check c JOIN variation_check_finding f ON f.check_id = c.id "
                        + "WHERE " + variation.sql() + " GROUP BY f.rule_id", variation.args(),
                (rs, i) -> new RuleFindingRow(Pack.VARIATION_CHECKS.id(), rs.getString("rule_id"), FeedbackSource.RULE.name(),
                        rs.getLong("n"))));
        return rows;
    }

    /**
     * Retained trademark results of the period with at least one {@code HIGH} finding of each source (the level at which
     * {@code batch-check} holds a row by default). A result held by two sources is counted under both.
     */
    public List<SourceHoldRow> trademarkHoldsBySource(Scope scope) {
        Clause where = resultClause(scope, true, "f.risk = 'HIGH'");
        return query("SELECT " + SOURCE_OF_TRADEMARK + " AS source, count(DISTINCT c.id) AS n FROM trademark_check c "
                        + "JOIN trademark_check_finding f ON f.check_id = c.id WHERE " + where.sql() + " GROUP BY 1 ORDER BY 1",
                where.args(), (rs, i) -> new SourceHoldRow(rs.getString("source"), rs.getLong("n")));
    }

    /** Retained trademark results of the period with at least one {@code HIGH} finding, whatever its source. */
    public long trademarkHolds(Scope scope) {
        Clause where = resultClause(scope, true, "f.risk = 'HIGH'");
        Long n = jdbc.queryForObject("SELECT count(DISTINCT c.id) FROM trademark_check c JOIN trademark_check_finding f "
                + "ON f.check_id = c.id WHERE " + where.sql(), Long.class, where.args().toArray());
        return n == null ? 0 : n;
    }

    // ---- shared

    private record Clause(String sql, List<Object> args) {
    }

    private static Clause verdictClause(Scope scope) {
        StringBuilder sql = new StringBuilder("v.client_id = ? AND v.updated_at >= ? AND v.updated_at < ?");
        List<Object> args = new ArrayList<>(List.of(scope.clientId(), utc(scope.from()), utc(scope.toExclusive())));
        if (scope.marketplace() != null) {
            sql.append(" AND v.marketplace_code = ?");
            args.add(scope.marketplace());
        }
        return new Clause(sql.toString(), args);
    }

    private static Clause resultClause(Scope scope, boolean retained, String extra, Object... extraArgs) {
        StringBuilder sql = new StringBuilder("c.client_id = ? AND c.created_at >= ? AND c.created_at < ?");
        List<Object> args = new ArrayList<>(List.of(scope.clientId(), utc(scope.from()), utc(scope.toExclusive())));
        if (scope.marketplace() != null) {
            sql.append(" AND c.marketplace_code = ?");
            args.add(scope.marketplace());
        }
        if (retained) {
            sql.append(" AND c.expires_at > ?");
            args.add(utc(scope.now()));
        }
        if (extra != null) {
            sql.append(" AND ").append(extra);
            args.addAll(List.of(extraArgs));
        }
        return new Clause(sql.toString(), args);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private <T> List<T> query(String sql, List<Object> args, RowMapper<T> mapper) {
        return jdbc.query(sql, mapper, args.toArray());
    }
}
