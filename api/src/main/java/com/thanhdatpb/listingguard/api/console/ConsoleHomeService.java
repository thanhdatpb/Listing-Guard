package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.feedback.FeedbackSource;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsQueries;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsService;
import com.thanhdatpb.listingguard.api.feedback.FeedbackVerdict;
import com.thanhdatpb.listingguard.api.report.HealthReport;
import com.thanhdatpb.listingguard.api.report.HealthReportService;
import com.thanhdatpb.listingguard.api.report.ReportQuery;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Counts what the console home page shows (ADR-034), for the signed-in account's client. The listing numbers and the eight-week
 * verdicts come from {@link HealthReportService}, the trademark holds by source and the week's verdicts from
 * {@link FeedbackStatsQueries}, so the home page, the report and the statistics page count one thing one way. It writes nothing,
 * needs no plan pack and costs no unit.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleHomeService {

    /** How many ISO weeks the chart shows, the current one included. */
    static final int WEEKS = 8;

    /** How many rules the "most often marked false positive" list shows. */
    static final int TOP = 5;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM", Locale.ROOT);

    private final HealthReportService reports;
    private final FeedbackStatsQueries feedback;
    private final Clock clock;

    public ConsoleHomeService(HealthReportService reports, FeedbackStatsQueries feedback, Clock clock) {
        this.reports = reports;
        this.feedback = feedback;
        this.clock = clock;
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
        long catalog = 0;
        long rule = 0;
        for (FeedbackStatsQueries.SourceHoldRow row : feedback.trademarkHoldsBySource(scope)) {
            if (FeedbackSource.CATALOG.name().equals(row.source())) {
                catalog = row.checks();
            } else if (FeedbackSource.RULE.name().equals(row.source())) {
                rule = row.checks();
            }
        }

        boolean empty = report.packs().listingRules().checks() == 0 && report.packs().trademarkRisk().checks() == 0
                && report.packs().variationChecks().checks() == 0 && report.feedback().verdicts() == 0;
        List<HealthReport.FalsePositiveRuleView> top = report.feedback().topFalsePositiveRules().items().stream().limit(TOP).toList();
        return new HomeView(monday, today, empty, current.listingRules().checks(), errorChecks(current.listingRules()),
                feedback.trademarkHolds(scope), catalog, rule, verdicts.verdicts(), falsePositives, verdicts.falsePositiveRate(),
                WeeklyChart.of(weeks), top);
    }

    private static long errorChecks(HealthReport.ListingRulesView view) {
        return view.findings().stream().filter(severity -> "ERROR".equals(severity.severity()))
                .mapToLong(HealthReport.SeverityCountView::checksAffected).sum();
    }
}
