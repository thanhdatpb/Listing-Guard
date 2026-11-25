package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.check.CheckScope;
import com.thanhdatpb.listingguard.api.report.ReportQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Counts successful Console listing checks without accepting listing data or identity beyond the client. */
@Service
public class PilotMetrics {
    public record Observation(String risk, boolean rechecked) { }

    public record Day(LocalDate date, long checked, long high, long medium, long low, long rechecked) {
        public long unranked() {
            return checked - high - medium - low;
        }

        public Double rate(long count) {
            long ranked = high + medium + low;
            return ranked == 0 ? null : (double) count / ranked;
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ConsolePilot pilot;

    public PilotMetrics(JdbcTemplate jdbc, Clock clock, ConsolePilot pilot) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.pilot = pilot;
    }

    public void record(UUID clientId, String marketplace, Collection<Observation> outcomes) {
        record(clientId, marketplace, CheckScope.Kind.FULL, outcomes);
    }

    /** As above, counted under the scope the checks were made in (ADR-052): a content check is never added to a full day. */
    public void record(UUID clientId, String marketplace, CheckScope.Kind scope, Collection<Observation> outcomes) {
        if (!pilot.enabled() || outcomes.isEmpty()) {
            return;
        }
        long high = outcomes.stream().filter(outcome -> "HIGH".equals(outcome.risk())).count();
        long medium = outcomes.stream().filter(outcome -> "MEDIUM".equals(outcome.risk())).count();
        long low = outcomes.stream().filter(outcome -> "LOW".equals(outcome.risk())).count();
        long rechecked = outcomes.stream().filter(Observation::rechecked).count();
        jdbc.update("""
                INSERT INTO seller_pilot_daily (client_id, check_day, marketplace_code, scope, checked, high, medium, low, rechecked)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (client_id, check_day, marketplace_code, scope) DO UPDATE SET
                    checked = seller_pilot_daily.checked + EXCLUDED.checked,
                    high = seller_pilot_daily.high + EXCLUDED.high,
                    medium = seller_pilot_daily.medium + EXCLUDED.medium,
                    low = seller_pilot_daily.low + EXCLUDED.low,
                    rechecked = seller_pilot_daily.rechecked + EXCLUDED.rechecked
                """, clientId, LocalDate.now(clock.withZone(ZoneOffset.UTC)), marketplace, scope.name(), outcomes.size(), high, medium,
                low, rechecked);
    }

    /** The days of a period for the checks made in full: what the report always showed. */
    public List<Day> days(UUID clientId, ReportQuery query) {
        return days(clientId, query, CheckScope.Kind.FULL);
    }

    /** The days of a period for the checks made under one scope (ADR-052); the scopes are never added together. */
    public List<Day> days(UUID clientId, ReportQuery query, CheckScope.Kind scope) {
        return jdbc.query("""
                SELECT day::date AS report_day, coalesce(sum(checked), 0), coalesce(sum(high), 0),
                       coalesce(sum(medium), 0), coalesce(sum(low), 0), coalesce(sum(rechecked), 0)
                FROM generate_series(?::date, ?::date, interval '1 day') AS calendar(day)
                LEFT JOIN seller_pilot_daily p ON p.check_day = day::date AND p.client_id = ?
                     AND (?::text IS NULL OR p.marketplace_code = ?) AND p.scope = ?
                GROUP BY day ORDER BY day
                """, (rs, index) -> new Day(rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6)), query.from(), query.to(), clientId, query.marketplace(), query.marketplace(),
                scope.name());
    }
}
