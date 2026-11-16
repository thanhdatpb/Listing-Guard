package com.thanhdatpb.listingguard.api.feedback;

import com.thanhdatpb.listingguard.api.feedback.FeedbackStats.ReviewerFeedbackView;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStats.RuleFeedbackView;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStats.VerdictTotalsView;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsQueries.RuleFindingRow;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsQueries.RuleVerdictRow;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsQueries.Scope;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsQueries.VerdictRow;
import com.thanhdatpb.listingguard.api.report.HealthReport;
import com.thanhdatpb.listingguard.api.report.HealthReportService;
import com.thanhdatpb.listingguard.api.report.ReportQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the feedback statistics of one client over a period (ADR-034): the one place that does, for the endpoint, the console
 * statistics page and its CSV. It reads in one read-only {@code REPEATABLE READ} transaction (one snapshot), writes nothing and
 * needs no plan pack (ADR-013). The period rules are the report's ({@link HealthReportService#query}), so a period the report
 * refuses is refused here too.
 */
@Service
public class FeedbackStatsService {

    /** The default period of the statistics page: the 28 days that end today, today included. */
    public static final int DEFAULT_DAYS = 28;

    private static final Comparator<RuleFeedbackView> RULE_ORDER = Comparator
            .comparingLong(RuleFeedbackView::falsePositive).reversed()
            .thenComparing(Comparator.comparingLong(RuleFeedbackView::verdicts).reversed())
            .thenComparing(Comparator.comparingLong(RuleFeedbackView::findings).reversed())
            .thenComparing(RuleFeedbackView::pack)
            .thenComparing(RuleFeedbackView::ruleId)
            .thenComparing(RuleFeedbackView::source);

    private final FeedbackStatsQueries queries;
    private final HealthReportService reports;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    public FeedbackStatsService(FeedbackStatsQueries queries, HealthReportService reports,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.queries = queries;
        this.reports = reports;
        this.clock = clock;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** Checks the period and the marketplace of a request, exactly as the report does. */
    public ReportQuery query(String from, String to, String marketplace) {
        return reports.query(from, to, marketplace);
    }

    /**
     * The period a caller gets when it names none: {@value #DEFAULT_DAYS} days that end today (UTC), or fewer when the deployment's
     * {@code listing-guard.reports.max-days} is smaller.
     */
    public ReportQuery defaultQuery(String marketplace) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        int days = Math.min(DEFAULT_DAYS, maxDays());
        return reports.query(today.minusDays(days - 1L).toString(), today.toString(), marketplace);
    }

    public int maxDays() {
        return reports.limits().maxDays();
    }

    public FeedbackStats stats(UUID clientId, ReportQuery query) {
        Instant now = clock.instant();
        Scope scope = new Scope(clientId, query.start(), query.endExclusive(), query.marketplace(), now);
        return snapshot.execute(status -> build(scope, query, now));
    }

    private FeedbackStats build(Scope scope, ReportQuery query, Instant now) {
        VerdictTotalsView totals = totals(queries.verdicts(scope));

        Map<String, long[]> byKey = new LinkedHashMap<>();
        Map<String, String[]> names = new LinkedHashMap<>();
        for (RuleFindingRow row : queries.findingsByRule(scope)) {
            String key = row.pack() + "\u0000" + row.ruleId() + "\u0000" + row.source();
            names.putIfAbsent(key, new String[] {row.pack(), row.ruleId(), row.source()});
            byKey.computeIfAbsent(key, k -> new long[4])[0] += row.findings();
        }
        for (RuleVerdictRow row : queries.verdictsByRule(scope)) {
            String key = row.pack() + "\u0000" + row.ruleId() + "\u0000" + row.source();
            names.putIfAbsent(key, new String[] {row.pack(), row.ruleId(), row.source()});
            long[] counts = byKey.computeIfAbsent(key, k -> new long[4]);
            counts[1] += row.correct();
            counts[2] += row.falsePositive();
            counts[3] += row.notUseful();
        }
        List<RuleFeedbackView> rules = new ArrayList<>();
        byKey.forEach((key, counts) -> {
            String[] name = names.get(key);
            long verdicts = counts[1] + counts[2] + counts[3];
            rules.add(new RuleFeedbackView(name[0], name[1], name[2], counts[0], verdicts, counts[1], counts[2], counts[3],
                    rate(counts[2], verdicts)));
        });
        rules.sort(RULE_ORDER);

        List<ReviewerFeedbackView> reviewers = queries.verdictsByReviewer(scope).stream()
                .map(row -> new ReviewerFeedbackView(row.kind(), row.id(), row.display(), row.total(), row.correct(),
                        row.falsePositive(), row.notUseful()))
                .toList();

        return new FeedbackStats(new HealthReport.PeriodView(query.from(), query.to(), query.days()), query.marketplace(),
                now.truncatedTo(ChronoUnit.SECONDS), totals, List.copyOf(rules), reviewers);
    }

    /** The three verdicts, always, in the order of the enum; any other stored value is kept, never dropped. */
    public static VerdictTotalsView totals(List<VerdictRow> rows) {
        Map<String, Long> byVerdict = new LinkedHashMap<>();
        for (FeedbackVerdict verdict : FeedbackVerdict.values()) {
            byVerdict.put(verdict.name(), 0L);
        }
        long total = 0;
        for (VerdictRow row : rows) {
            byVerdict.merge(row.verdict(), row.count(), Long::sum);
            total += row.count();
        }
        return new VerdictTotalsView(total, byVerdict, rate(byVerdict.get(FeedbackVerdict.FALSE_POSITIVE.name()), total));
    }

    /** {@code part / whole} to four decimals, or null when {@code whole} is zero (no verdict is never a rate of zero). */
    public static Double rate(long part, long whole) {
        if (whole == 0) {
            return null;
        }
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP).doubleValue();
    }
}
