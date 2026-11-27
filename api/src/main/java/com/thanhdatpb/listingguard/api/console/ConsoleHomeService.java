package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.console.reaudit.ReauditStore;
import com.thanhdatpb.listingguard.api.feedback.FeedbackSource;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsQueries;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsService;
import com.thanhdatpb.listingguard.api.feedback.FeedbackVerdict;
import com.thanhdatpb.listingguard.api.report.HealthReport;
import com.thanhdatpb.listingguard.api.report.HealthReportService;
import com.thanhdatpb.listingguard.api.report.ReportQuery;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Counts what the console home page shows (ADR-034, ADR-041), for the signed-in account's client. The listing numbers, the eight-week
 * verdicts, the most frequent rules and the versions in force come from {@link HealthReportService}, the trademark holds by source and
 * the week's verdicts from {@link FeedbackStatsQueries}, so the home page, the report and the statistics page count one thing one way.
 * The list of results to look at is {@link ConsoleAttentionQueries}; the watchlist, the catalog and the latest re-audit are read
 * through the services that own them. It writes nothing, needs no plan pack and costs no unit.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleHomeService {

    /** How many ISO weeks the chart shows, the current one included. */
    static final int WEEKS = 8;

    /** How many rules the "most frequent rules" table shows. */
    static final int TOP = 5;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM", Locale.ROOT);

    private final HealthReportService reports;
    private final FeedbackStatsQueries feedback;
    private final Clock clock;
    private final com.thanhdatpb.listingguard.api.llm.LlmClient llm;
    private final ConsoleAttentionQueries attention;
    private final TrademarkCatalogRuntime catalog;
    private final WatchlistService watchlist;
    private final ReauditStore reaudit;
    private final JdbcTemplate jdbc;

    public ConsoleHomeService(HealthReportService reports, FeedbackStatsQueries feedback, Clock clock,
                              com.thanhdatpb.listingguard.api.llm.LlmClient llm, ConsoleAttentionQueries attention,
                              TrademarkCatalogRuntime catalog, WatchlistService watchlist, ReauditStore reaudit,
                              JdbcTemplate jdbc) {
        this.llm = llm;
        this.reports = reports;
        this.feedback = feedback;
        this.clock = clock;
        this.attention = attention;
        this.catalog = catalog;
        this.watchlist = watchlist;
        this.reaudit = reaudit;
        this.jdbc = jdbc;
    }

    public HomeView home(UUID clientId) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate first = monday.minusWeeks(WEEKS - 1L);
        HealthReport report = reports.summary(clientId, new ReportQuery(first, today, null));

        List<WeeklyChart.Week> weeks = new ArrayList<>();
        for (HealthReport.WeekView week : report.byWeek()) {
            weeks.add(new WeeklyChart.Week(week.isoWeek(), DAY.format(week.from()), week.partial() || week.to().equals(today),
                    week.listingRules().checks(), errorChecks(week.listingRules())));
        }
        HealthReport.WeekView current = report.byWeek().get(report.byWeek().size() - 1);

        ReportQuery thisWeek = new ReportQuery(monday, today, null);
        FeedbackStatsQueries.Scope scope = new FeedbackStatsQueries.Scope(clientId, thisWeek.start(), thisWeek.endExclusive(), null,
                now);
        var verdicts = FeedbackStatsService.totals(feedback.verdicts(scope));
        long falsePositives = verdicts.byVerdict().get(FeedbackVerdict.FALSE_POSITIVE.name());
        long fromCatalog = 0;
        long fromWatchlist = 0;
        long fromRule = 0;
        for (FeedbackStatsQueries.SourceHoldRow row : feedback.trademarkHoldsBySource(scope)) {
            if (FeedbackSource.CATALOG.name().equals(row.source())) {
                fromCatalog = row.checks();
            } else if (FeedbackSource.WATCHLIST.name().equals(row.source())) {
                fromWatchlist = row.checks();
            } else if (FeedbackSource.RULE.name().equals(row.source())) {
                fromRule = row.checks();
            }
        }

        boolean empty = report.packs().listingRules().checks() == 0 && report.packs().trademarkRisk().checks() == 0
                && report.packs().variationChecks().checks() == 0 && report.feedback().verdicts() == 0;
        return new HomeView(monday, today, empty, current.listingRules().checks(), errorChecks(current.listingRules()),
                feedback.trademarkHolds(scope), fromCatalog, fromWatchlist, fromRule, verdicts.verdicts(), falsePositives,
                verdicts.falsePositiveRate(), WeeklyChart.of(weeks), topRules(report), attention.latest(clientId, now),
                checkData(clientId, report), llm.usage());
    }

    /**
     * The rules with the most findings, with the false positives reported on each. The report lists the rules marked false positive
     * only up to its limit: when more were marked than it lists, a rule missing from that list is "unknown", never zero.
     */
    private static List<HomeView.TopRule> topRules(HealthReport report) {
        HealthReport.RuleRankingView ranking = report.packs().listingRules().topRules();
        if (ranking == null) {
            return List.of();
        }
        HealthReport.FalsePositiveRankingView listed = report.feedback().topFalsePositiveRules();
        boolean complete = listed.distinctRules() <= listed.items().size();
        Map<String, Long> marked = new HashMap<>();
        for (HealthReport.FalsePositiveRuleView item : listed.items()) {
            if ("listing-rules".equals(item.pack())) {
                marked.merge(item.ruleId(), item.falsePositives(), Long::sum);
            }
        }
        List<HomeView.TopRule> rules = new ArrayList<>();
        for (HealthReport.RuleCountView item : ranking.items()) {
            if (rules.size() == TOP) {
                break;
            }
            Long count = marked.get(item.ruleId());
            rules.add(new HomeView.TopRule(item.ruleId(), item.severity(), item.findings(),
                    count != null ? count : complete ? Long.valueOf(0) : null));
        }
        return List.copyOf(rules);
    }

    /** What the checks ran on, from the services that own each part. A part that cannot be read is left out, never guessed. */
    HomeView.CheckData checkData(UUID clientId, HealthReport report) {
        TrademarkCatalogRuntime.Status status = catalog.demoStatus(clientId).orElseGet(catalog::status);
        boolean ready = status.state() == TrademarkCatalogRuntime.State.READY;
        String source = null;
        LocalDate date = null;
        if (ready) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT source_name, source_date FROM trademark_catalog_version WHERE version = ?", status.detail());
            if (!rows.isEmpty()) {
                source = (String) rows.get(0).get("source_name");
                date = ((java.sql.Date) rows.get(0).get("source_date")).toLocalDate();
            }
        }
        ReauditStore.Run last = reaudit.recent(clientId, 1).stream().findFirst().orElse(null);
        List<HomeView.RulesetVersion> rulesets = new ArrayList<>();
        for (HealthReport.RulesetView ruleset : report.versions().rulesets()) {
            if ("listing-rules".equals(ruleset.pack())) {
                rulesets.add(new HomeView.RulesetVersion(ruleset.marketplace(), ruleset.version()));
            }
        }
        return new HomeView.CheckData(status.state().name(), ready ? status.detail() : null, source, date,
                watchlist.active(clientId).watchlist().entries().size(), last == null ? null : last.startedAt(),
                last == null ? null : Long.valueOf(last.findingsNew()), List.copyOf(rulesets));
    }

    static long errorChecks(HealthReport.ListingRulesView view) {
        return view.findings().stream().filter(severity -> "ERROR".equals(severity.severity()))
                .mapToLong(HealthReport.SeverityCountView::checksAffected).sum();
    }
}
