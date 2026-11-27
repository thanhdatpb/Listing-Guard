package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.report.ReportQuery;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** UTC weeks of successful screenings, limited to the screened client and report period. */
@Service
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.phrase-screening.enabled:false}")
public class PhraseConsoleMetrics {

    public record Week(LocalDate week, long runs, long submitted, long block, long review, long clear) {
        public Double share(long count) {
            long classified = block + review + clear;
            return classified == 0 ? null : (double) count / classified;
        }
    }

    private final JdbcTemplate jdbc;

    public PhraseConsoleMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Week> weeks(UUID client, ReportQuery query) {
        String marketplace = query.marketplace();
        return jdbc.query("""
                SELECT date_trunc('week', created_at AT TIME ZONE 'UTC')::date AS week, count(*) AS runs,
                       sum(phrase_count) AS submitted, sum(block_count) AS block,
                       sum(review_count) AS review, sum(clear_count) AS clear
                FROM phrase_screening_run
                WHERE client_id = ? AND created_at >= ? AND created_at < ?
                """ + (marketplace == null ? "" : " AND marketplace_code = ?") + " GROUP BY 1 ORDER BY 1",
                (rs, index) -> new Week(rs.getObject("week", LocalDate.class), rs.getLong("runs"), rs.getLong("submitted"),
                        rs.getLong("block"), rs.getLong("review"), rs.getLong("clear")),
                marketplace == null ? new Object[]{client, start(query), end(query)} : new Object[]{client, start(query), end(query), marketplace});
    }

    static void appendCsv(StringBuilder csv, ConsoleMessages messages, List<Week> weeks) {
        StatsCsv.line(csv, messages.get("phrase.stats.heading"));
        StatsCsv.line(csv, messages.get("phrase.stats.week"), messages.get("phrase.stats.runs"), messages.get("phrase.stats.submitted"),
                messages.get("phrase.verdict.BLOCK"), messages.get("phrase.verdict.REVIEW"), messages.get("phrase.verdict.CLEAR"));
        for (Week week : weeks) {
            StatsCsv.line(csv, week.week().toString(), Long.toString(week.runs()), Long.toString(week.submitted()),
                    StatsCsv.rate(week.share(week.block())), StatsCsv.rate(week.share(week.review())),
                    StatsCsv.rate(week.share(week.clear())));
        }
    }

    private static OffsetDateTime start(ReportQuery query) {
        return query.start().atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime end(ReportQuery query) {
        return query.endExclusive().atOffset(ZoneOffset.UTC);
    }
}
