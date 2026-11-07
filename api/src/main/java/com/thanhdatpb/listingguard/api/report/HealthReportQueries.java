package com.thanhdatpb.listingguard.api.report;

import com.thanhdatpb.listingguard.api.plan.Pack;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Every SQL statement of the listing health report (ADR-018), and nothing else reads the result tables for it. All of them
 * are aggregates ({@code GROUP BY}): no result row or finding is loaded, and no column that holds text a client typed is
 * selected (only counts, the enumerations, a rule id and a catalog mark).
 *
 * <p>Every statement starts with {@code c.client_id = ?}, so another client's rows are not reachable (ADR-007). A week is
 * the ISO week (PostgreSQL's {@code date_trunc('week')} starts on Monday) of the UTC date of {@code created_at}. Tables that
 * delete results after a retention period are counted only while {@code expires_at} is after {@link Scope#now()}, whatever
 * the cleanup job has done (ADR-009).
 */
@Component
public class HealthReportQueries {

    /** What every statement filters by. {@code marketplace} is null for all marketplaces. */
    record Scope(UUID clientId, Instant from, Instant toExclusive, String marketplace, Instant now) {
    }

    record ListingRow(LocalDate week, String status, long checks, long scored, long incomplete, long ready, long scoreTotal) {
    }

    record SeverityRow(LocalDate week, String severity, long findings, long checks) {
    }

    /** {@code distinct} is the number of groups before the list was cut. */
    record RuleRow(String ruleId, String severity, long findings, long checks, long distinct) {
    }

    record TrademarkRow(LocalDate week, String completeness, String riskLevel, long checks) {
    }

    /** {@code highestRank} is 3 for HIGH, 2 for MEDIUM and 1 for LOW. */
    record MarkRow(String mark, long findings, long checks, int highestRank, long distinct) {
    }

    record OutcomeRow(LocalDate week, String completeness, long checks) {
    }

    record ScreeningRow(LocalDate week, String completeness, String trademarkStatus, String riskLevel, long checks) {
    }

    private record Clause(String sql, List<Object> args) {
    }

    private static final String WEEK = "date_trunc('week', c.created_at AT TIME ZONE 'UTC')::date";

    private static final String SEVERITY_ORDER = "CASE f.severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END";

    private final JdbcTemplate jdbc;

    public HealthReportQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- listing-rules (listing_check, finding): no retention

    List<ListingRow> listingByWeek(Scope scope) {
        Clause where = clause(scope, false, "c.pack_id = ?", Pack.LISTING_RULES.id());
        return query("""
                SELECT %s AS week, c.status, count(*) AS checks,
                       count(*) FILTER (WHERE c.readiness_status = 'SCORED') AS scored,
                       count(*) FILTER (WHERE c.readiness_status = 'INCOMPLETE') AS incomplete,
                       count(*) FILTER (WHERE c.readiness_status = 'SCORED' AND c.readiness_ready) AS ready,
                       COALESCE(sum(c.readiness_score) FILTER (WHERE c.readiness_status = 'SCORED'), 0) AS score_total
                FROM listing_check c WHERE %s GROUP BY 1, 2 ORDER BY 1, 2""".formatted(WEEK, where.sql()), where.args(),
                (rs, i) -> new ListingRow(rs.getObject("week", LocalDate.class), rs.getString("status"), rs.getLong("checks"),
                        rs.getLong("scored"), rs.getLong("incomplete"), rs.getLong("ready"), rs.getLong("score_total")));
    }

    List<SeverityRow> listingSeverityByWeek(Scope scope) {
        return severityByWeek("listing_check", "finding", "", scope, false, "c.pack_id = ?", Pack.LISTING_RULES.id());
    }

    List<RuleRow> listingTopRules(Scope scope, int limit) {
        return topRules("listing_check", "finding", "", scope, false, limit, "c.pack_id = ?", Pack.LISTING_RULES.id());
    }

    /** The readiness versions the results of the period carry, sorted. */
    List<String> readinessVersions(Scope scope) {
        Clause where = clause(scope, false, "c.pack_id = ? AND c.readiness_version IS NOT NULL", Pack.LISTING_RULES.id());
        return query("SELECT DISTINCT c.readiness_version FROM listing_check c WHERE " + where.sql()
                + " ORDER BY 1", where.args(), (rs, i) -> rs.getString(1));
    }

    // ---- trademark-risk (trademark_check, trademark_check_finding): retained

    List<TrademarkRow> trademarkByWeek(Scope scope) {
        Clause where = clause(scope, true);
        return query("SELECT " + WEEK + " AS week, c.completeness, c.risk_level, count(*) AS checks FROM trademark_check c WHERE "
                        + where.sql() + " GROUP BY 1, 2, 3 ORDER BY 1, 2, 3", where.args(),
                (rs, i) -> new TrademarkRow(rs.getObject("week", LocalDate.class), rs.getString("completeness"),
                        rs.getString("risk_level"), rs.getLong("checks")));
    }

    /** The catalog marks matched most often. A finding with no mark (the colour and place rules) is left out. */
    List<MarkRow> trademarkTopMarks(Scope scope, int limit) {
        Clause where = clause(scope, true);
        List<Object> args = new ArrayList<>(where.args());
        args.add(limit);
        return query("""
                SELECT f.matched_mark AS mark, count(*) AS findings, count(DISTINCT f.check_id) AS checks,
                       max(CASE f.risk WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END) AS highest,
                       count(*) OVER () AS distinct_total
                FROM trademark_check c JOIN trademark_check_finding f ON f.check_id = c.id
                WHERE %s AND f.matched_mark IS NOT NULL
                GROUP BY f.matched_mark
                ORDER BY findings DESC, checks DESC, f.matched_mark COLLATE "C"
                LIMIT ?""".formatted(where.sql()), args,
                (rs, i) -> new MarkRow(rs.getString("mark"), rs.getLong("findings"), rs.getLong("checks"), rs.getInt("highest"),
                        rs.getLong("distinct_total")));
    }

    // ---- variation-checks (variation_check, variation_check_finding): retained

    List<OutcomeRow> variationByWeek(Scope scope) {
        Clause where = clause(scope, true);
        return query("SELECT " + WEEK + " AS week, c.completeness, count(*) AS checks FROM variation_check c WHERE "
                        + where.sql() + " GROUP BY 1, 2 ORDER BY 1, 2", where.args(),
                (rs, i) -> new OutcomeRow(rs.getObject("week", LocalDate.class), rs.getString("completeness"), rs.getLong("checks")));
    }

    List<SeverityRow> variationSeverityByWeek(Scope scope) {
        return severityByWeek("variation_check", "variation_check_finding", "", scope, true, null);
    }

    List<RuleRow> variationTopRules(Scope scope, int limit) {
        return topRules("variation_check", "variation_check_finding", "", scope, true, limit, null);
    }

    // ---- personalization-screening (personalization_screening, ..._finding): retained, character rules only

    List<ScreeningRow> personalizationByWeek(Scope scope) {
        Clause where = clause(scope, true);
        return query("SELECT " + WEEK + " AS week, c.completeness, c.trademark_status, c.trademark_risk_level, count(*) AS checks "
                        + "FROM personalization_screening c WHERE " + where.sql() + " GROUP BY 1, 2, 3, 4 ORDER BY 1, 2, 3, 4",
                where.args(), (rs, i) -> new ScreeningRow(rs.getObject("week", LocalDate.class), rs.getString("completeness"),
                        rs.getString("trademark_status"), rs.getString("trademark_risk_level"), rs.getLong("checks")));
    }

    List<SeverityRow> personalizationSeverityByWeek(Scope scope) {
        return severityByWeek("personalization_screening", "personalization_screening_finding", "AND f.source = 'TEXT'",
                scope, true, null);
    }

    List<RuleRow> personalizationTopRules(Scope scope, int limit) {
        return topRules("personalization_screening", "personalization_screening_finding", "AND f.source = 'TEXT'",
                scope, true, limit, null);
    }

    // ---- shared shapes. The table names and the findings filter are constants of this class, never input.

    private List<SeverityRow> severityByWeek(String results, String findings, String findingFilter, Scope scope,
                                             boolean retained, String extraCondition, Object... extraArgs) {
        Clause where = clause(scope, retained, extraCondition, extraArgs);
        return query("SELECT " + WEEK + " AS week, f.severity, count(*) AS findings, count(DISTINCT f.check_id) AS checks FROM "
                        + results + " c JOIN " + findings + " f ON f.check_id = c.id WHERE " + where.sql() + " " + findingFilter
                        + " GROUP BY 1, 2 ORDER BY 1, 2", where.args(),
                (rs, i) -> new SeverityRow(rs.getObject("week", LocalDate.class), rs.getString("severity"), rs.getLong("findings"),
                        rs.getLong("checks")));
    }

    /**
     * The rules with the most findings, keyed by rule id and severity. A finding with no rule id (a row made before V2) is left
     * out. The order is total, so a tie never changes between two calls: findings, results affected, rule id by code point
     * ({@code COLLATE "C"}), severity.
     */
    private List<RuleRow> topRules(String results, String findings, String findingFilter, Scope scope, boolean retained, int limit,
                                   String extraCondition, Object... extraArgs) {
        Clause where = clause(scope, retained, extraCondition, extraArgs);
        List<Object> args = new ArrayList<>(where.args());
        args.add(limit);
        return query("SELECT f.rule_id, f.severity, count(*) AS findings, count(DISTINCT f.check_id) AS checks, "
                        + "count(*) OVER () AS distinct_total FROM " + results + " c JOIN " + findings
                        + " f ON f.check_id = c.id WHERE " + where.sql() + " AND f.rule_id IS NOT NULL " + findingFilter
                        + " GROUP BY f.rule_id, f.severity ORDER BY findings DESC, checks DESC, f.rule_id COLLATE \"C\", "
                        + SEVERITY_ORDER + " LIMIT ?", args,
                (rs, i) -> new RuleRow(rs.getString("rule_id"), rs.getString("severity"), rs.getLong("findings"),
                        rs.getLong("checks"), rs.getLong("distinct_total")));
    }

    private static Clause clause(Scope scope, boolean retained) {
        return clause(scope, retained, null);
    }

    /** The condition every statement shares, on the table aliased {@code c}, with {@code extra} appended when given. */
    private static Clause clause(Scope scope, boolean retained, String extra, Object... extraArgs) {
        StringBuilder sql = new StringBuilder("c.client_id = ? AND c.created_at >= ? AND c.created_at < ?");
        List<Object> args = new ArrayList<>();
        args.add(scope.clientId());
        args.add(utc(scope.from()));
        args.add(utc(scope.toExclusive()));
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
