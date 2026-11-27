package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsQueries;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsService;
import com.thanhdatpb.listingguard.api.report.HealthReport;
import com.thanhdatpb.listingguard.api.report.HealthReportService;
import com.thanhdatpb.listingguard.api.report.ReportQuery;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class RoleHomeService {
    public record Mark(String name, boolean watchlist, long checks) {}
    public record Overview(ReportQuery period, Instant generatedAt, long checks, long errors, long catalogHolds,
                           long watchlistHolds, Long keywords, Long keywordBlocks, long feedbackCount, Double falsePositiveRate,
                           long activeAccounts, WeeklyChart chart, List<HealthReport.RuleCountView> rules, List<Mark> marks,
                           HomeView.CheckData data, boolean catalogGatePassed) {
        public boolean empty() {
            return checks == 0 && catalogHolds == 0 && watchlistHolds == 0 && (keywords == null || keywords == 0)
                    && feedbackCount == 0 && activeAccounts == 0;
        }
    }

    public record Activity(String pack, UUID checkId, Instant at, String sku, String marketplace, long rows, long blocked) {}
    public record Work(List<Activity> recent, List<Activity> needsFix, boolean guideHidden) {}

    private static final String MARKET = "(CAST(:marketplace AS text) IS NULL OR marketplace_code = :marketplace)";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM", java.util.Locale.ROOT);

    private final HealthReportService reports;
    private final ConsoleHomeService home;
    private final FeedbackStatsQueries feedback;
    private final ObjectProvider<PhraseConsoleMetrics> phrases;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final String passedCatalog;

    public RoleHomeService(HealthReportService reports, ConsoleHomeService home, FeedbackStatsQueries feedback,
                           ObjectProvider<PhraseConsoleMetrics> phrases, NamedParameterJdbcTemplate jdbc, Clock clock,
                           @Value("${listing-guard.console.catalog-gate-passed-version:}") String passedCatalog) {
        this.reports = reports;
        this.home = home;
        this.feedback = feedback;
        this.phrases = phrases;
        this.jdbc = jdbc;
        this.clock = clock;
        this.passedCatalog = passedCatalog;
    }

    public ReportQuery period(int days) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return new ReportQuery(today.minusDays(days - 1L), today, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview overview(UUID client, ReportQuery period) {
        Instant now = clock.instant();
        HealthReport report = reports.summary(client, period);
        var listing = report.packs().listingRules();
        LocalDate chartStart = period.to().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(11);
        var chartPeriod = new ReportQuery(chartStart, period.to(), period.marketplace());
        var chartReport = period.equals(chartPeriod) ? report : reports.summary(client, chartPeriod);
        var weeks = chartReport.byWeek().stream().map(w -> new WeeklyChart.Week(w.isoWeek(), DAY.format(w.from()), w.partial(),
                w.listingRules().checks(), ConsoleHomeService.errorChecks(w.listingRules()))).toList();
        var scope = new FeedbackStatsQueries.Scope(client, period.start(), period.endExclusive(), period.marketplace(), now);
        var holds = feedback.trademarkHoldsBySource(scope);
        var verdicts = FeedbackStatsService.totals(feedback.verdicts(scope));
        var phraseMetrics = phrases.getIfAvailable();
        var phraseWeeks = phraseMetrics == null ? List.<PhraseConsoleMetrics.Week>of() : phraseMetrics.weeks(client, period);
        var params = new MapSqlParameterSource("client", client)
                .addValue("start", period.start().atOffset(ZoneOffset.UTC)).addValue("end", period.endExclusive().atOffset(ZoneOffset.UTC))
                .addValue("marketplace", period.marketplace()).addValue("now", now.atOffset(ZoneOffset.UTC))
                .addValue("phrases", phraseMetrics != null);
        var data = home.checkData(client, report);
        return new Overview(period, now, listing.checks(), ConsoleHomeService.errorChecks(listing), hold(holds, "CATALOG"),
                hold(holds, "WATCHLIST"), phraseMetrics == null ? null : phraseWeeks.stream()
                        .mapToLong(w -> w.block() + w.review() + w.clear()).sum(),
                phraseMetrics == null ? null : phraseWeeks.stream().mapToLong(PhraseConsoleMetrics.Week::block).sum(),
                verdicts.verdicts(), verdicts.falsePositiveRate(), activeAccounts(params), WeeklyChart.of(weeks, WeeklyChart.Week::checked),
                listing.topRules().items().stream().limit(3).toList(), topMarks(params), data,
                !passedCatalog.isBlank() && !TrademarkProperties.isDemo(passedCatalog) && passedCatalog.equals(data.catalogVersion()));
    }

    private static long hold(List<FeedbackStatsQueries.SourceHoldRow> holds, String source) {
        return holds.stream().filter(row -> source.equals(row.source())).mapToLong(FeedbackStatsQueries.SourceHoldRow::checks).sum();
    }

    private List<Mark> topMarks(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT f.matched_mark, f.rule_id = 'watchlist_match' AS watchlist, count(DISTINCT c.id) AS checks
                FROM (SELECT id FROM trademark_check WHERE client_id = :client AND created_at >= :start
                      AND created_at < :end AND expires_at > :now AND %s) c
                JOIN trademark_check_finding f ON f.check_id = c.id
                WHERE f.risk = 'HIGH' AND f.matched_mark IS NOT NULL
                GROUP BY 1, 2 ORDER BY checks DESC, f.matched_mark COLLATE "C", watchlist LIMIT 3
                """.formatted(MARKET), params, (rs, i) -> new Mark(rs.getString(1), rs.getBoolean(2), rs.getLong(3)));
    }

    private long activeAccounts(MapSqlParameterSource params) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM account a WHERE a.client_id = :client AND (
                    EXISTS (SELECT 1 FROM check_history_event WHERE client_id = :client AND actor_kind = 'ACCOUNT'
                        AND actor_id = a.id::text AND checked_at >= :start AND checked_at < :end AND %1$s)
                    OR EXISTS (SELECT 1 FROM finding_verdict WHERE client_id = :client AND account_id = a.id
                        AND updated_at >= :start AND updated_at < :end AND %1$s)
                    OR EXISTS (SELECT 1 FROM reaudit_run WHERE client_id = :client AND account_id = a.id
                        AND started_at >= :start AND started_at < :end AND (CAST(:marketplace AS text) IS NULL OR marketplace = :marketplace))
                    OR (:phrases AND EXISTS (SELECT 1 FROM phrase_screening_run WHERE client_id = :client AND account_id = a.id
                        AND created_at >= :start AND created_at < :end AND %1$s)))
                """.formatted(MARKET), params, Long.class);
    }

    public Work work(UUID client, UUID account) {
        var params = new MapSqlParameterSource("client", client).addValue("account", account)
                .addValue("actor", account.toString()).addValue("phrases", phrases.getIfAvailable() != null);
        var recent = jdbc.query("""
                SELECT pack_id, check_id, checked_at, sku, marketplace_code, row_count, blocked
                FROM (
                    SELECT h.pack_id, h.check_id, h.checked_at, h.sku, h.marketplace_code, h.row_count, 0 AS blocked,
                        h.id::text AS order_ref
                    FROM check_history_event h WHERE h.client_id = :client AND h.actor_kind = 'ACCOUNT' AND h.actor_id = :actor
                      AND (h.pack_id <> 'trademark-risk' OR EXISTS (SELECT 1 FROM trademark_check t WHERE t.client_id = :client
                           AND t.id = h.check_id AND t.expires_at > now()))
                      AND (h.pack_id <> 'variation-checks' OR EXISTS (SELECT 1 FROM variation_check v WHERE v.client_id = :client
                           AND v.id = h.check_id AND v.expires_at > now()))
                    UNION ALL
                    SELECT 'phrase-screening', NULL, created_at, NULL, marketplace_code, phrase_count, block_count, id::text
                    FROM phrase_screening_run WHERE :phrases AND client_id = :client AND account_id = :account
                    UNION ALL
                    SELECT 'reaudit', NULL, started_at, NULL, marketplace, rows_checked, findings_new, id::text
                    FROM reaudit_run WHERE client_id = :client AND account_id = :account
                ) activity
                ORDER BY checked_at DESC, pack_id, order_ref DESC LIMIT 5
                """, params, RoleHomeService::activity);
        var needsFix = jdbc.query("""
                WITH recent AS (
                   SELECT DISTINCT ON (c.marketplace_code, COALESCE(NULLIF(h.sku, ''), c.content_hash::text))
                     h.pack_id, h.check_id, h.checked_at, h.sku, h.marketplace_code, h.row_count
                   FROM check_history_event h JOIN listing_check c ON c.id = h.check_id AND c.client_id = :client
                   WHERE h.client_id = :client AND h.actor_kind = 'ACCOUNT' AND h.actor_id = :actor AND h.pack_id = 'listing-rules'
                   ORDER BY c.marketplace_code, COALESCE(NULLIF(h.sku, ''), c.content_hash::text), h.checked_at DESC, h.id DESC
                )
                SELECT recent.*, (SELECT count(*) FROM finding f WHERE f.check_id = recent.check_id AND f.severity = 'ERROR') AS blocked
                FROM recent WHERE EXISTS (SELECT 1 FROM finding f WHERE f.check_id = recent.check_id AND f.severity = 'ERROR')
                ORDER BY checked_at DESC, check_id DESC LIMIT 5
                """, params, RoleHomeService::activity);
        boolean hidden = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM console_home_guide WHERE account_id = :account)", params, Boolean.class));
        return new Work(recent, needsFix, hidden);
    }

    public void hideGuide(UUID client, UUID account) {
        jdbc.update("""
                INSERT INTO console_home_guide (account_id)
                SELECT id FROM account WHERE id = :account AND client_id = :client AND role = 'MEMBER'
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource("account", account).addValue("client", client));
    }

    private static Activity activity(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Activity(row.getString("pack_id"), row.getObject("check_id", UUID.class),
                row.getObject("checked_at", OffsetDateTime.class).toInstant(), row.getString("sku"),
                row.getString("marketplace_code"), row.getLong("row_count"), row.getLong("blocked"));
    }
}
