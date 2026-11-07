package com.thanhdatpb.listingguard.api.report;

import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.personalization.PersonalizationProperties;
import com.thanhdatpb.listingguard.api.personalization.PersonalizationRetentionProperties;
import com.thanhdatpb.listingguard.api.personalization.PersonalizationRulesets;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.report.HealthReport.CatalogView;
import com.thanhdatpb.listingguard.api.report.HealthReport.ListingRulesView;
import com.thanhdatpb.listingguard.api.report.HealthReport.MarkCountView;
import com.thanhdatpb.listingguard.api.report.HealthReport.MarkRankingView;
import com.thanhdatpb.listingguard.api.report.HealthReport.PacksView;
import com.thanhdatpb.listingguard.api.report.HealthReport.PeriodView;
import com.thanhdatpb.listingguard.api.report.HealthReport.PersonalizationView;
import com.thanhdatpb.listingguard.api.report.HealthReport.ReadinessSummaryView;
import com.thanhdatpb.listingguard.api.report.HealthReport.RuleCountView;
import com.thanhdatpb.listingguard.api.report.HealthReport.RuleRankingView;
import com.thanhdatpb.listingguard.api.report.HealthReport.RulesetView;
import com.thanhdatpb.listingguard.api.report.HealthReport.SeverityCountView;
import com.thanhdatpb.listingguard.api.report.HealthReport.TrademarkRiskView;
import com.thanhdatpb.listingguard.api.report.HealthReport.VariationChecksView;
import com.thanhdatpb.listingguard.api.report.HealthReport.VersionsView;
import com.thanhdatpb.listingguard.api.report.HealthReport.WeekView;
import com.thanhdatpb.listingguard.api.report.HealthReportQueries.ListingRow;
import com.thanhdatpb.listingguard.api.report.HealthReportQueries.MarkRow;
import com.thanhdatpb.listingguard.api.report.HealthReportQueries.OutcomeRow;
import com.thanhdatpb.listingguard.api.report.HealthReportQueries.RuleRow;
import com.thanhdatpb.listingguard.api.report.HealthReportQueries.Scope;
import com.thanhdatpb.listingguard.api.report.HealthReportQueries.ScreeningRow;
import com.thanhdatpb.listingguard.api.report.HealthReportQueries.SeverityRow;
import com.thanhdatpb.listingguard.api.report.HealthReportQueries.TrademarkRow;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRetentionProperties;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.variation.VariationProperties;
import com.thanhdatpb.listingguard.api.variation.VariationRetentionProperties;
import com.thanhdatpb.listingguard.api.variation.VariationRulesets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the listing health report (ADR-018). It is the only place that does: the endpoint, the {@code health-report} job and
 * the console page all call {@link #summary}, so they cannot give different numbers. It reads, in one read-only transaction at
 * {@code REPEATABLE READ} (one snapshot), counts that the queries aggregate in SQL, and writes nothing: no plan, no quota, no
 * charge (the report is the client's own stored numbers, ADR-013).
 *
 * <p>The numbers of a week come from the weekly rows of the queries, and the numbers of the period are built from the same
 * rows, so the totals are the sum of the weeks. Only the ranked lists have statements of their own.
 */
@Service
public class HealthReportService {

    // Keep each list equal to the Java enum or CHECK constraint it mirrors (a test holds them).
    static final List<String> LISTING_STATUSES = List.of("COMPLETED", "FAILED", "PENDING");
    static final List<String> COMPLETENESS = List.of("COMPLETE", "INCOMPLETE");
    static final List<String> RISK_LEVELS = List.of("HIGH", "MEDIUM", "LOW", "UNKNOWN");
    static final List<String> TRADEMARK_STATUSES = List.of("COMPLETE", "INCOMPLETE", "NOT_CONFIGURED");
    static final List<String> SEVERITIES = List.of("ERROR", "WARNING", "INFO");

    private final ReportsProperties properties;
    private final HealthReportQueries queries;
    private final NormalizerRegistry normalizers;
    private final ActiveRulesets rulesets;
    private final TrademarkRulesets trademarkRulesets;
    private final VariationRulesets variationRulesets;
    private final PersonalizationRulesets personalizationRulesets;
    private final TrademarkProperties trademarkProperties;
    private final VariationProperties variationProperties;
    private final PersonalizationProperties personalizationProperties;
    private final TrademarkCatalogRuntime trademarkRuntime;
    private final TrademarkRetentionProperties trademarkRetention;
    private final VariationRetentionProperties variationRetention;
    private final PersonalizationRetentionProperties personalizationRetention;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    public HealthReportService(ReportsProperties properties, HealthReportQueries queries, NormalizerRegistry normalizers,
                               ActiveRulesets rulesets, TrademarkRulesets trademarkRulesets, VariationRulesets variationRulesets,
                               PersonalizationRulesets personalizationRulesets, TrademarkProperties trademarkProperties,
                               VariationProperties variationProperties, PersonalizationProperties personalizationProperties,
                               TrademarkCatalogRuntime trademarkRuntime, TrademarkRetentionProperties trademarkRetention,
                               VariationRetentionProperties variationRetention,
                               PersonalizationRetentionProperties personalizationRetention,
                               PlatformTransactionManager transactionManager, Clock clock) {
        this.properties = properties;
        this.queries = queries;
        this.normalizers = normalizers;
        this.rulesets = rulesets;
        this.trademarkRulesets = trademarkRulesets;
        this.variationRulesets = variationRulesets;
        this.personalizationRulesets = personalizationRulesets;
        this.trademarkProperties = trademarkProperties;
        this.variationProperties = variationProperties;
        this.personalizationProperties = personalizationProperties;
        this.trademarkRuntime = trademarkRuntime;
        this.trademarkRetention = trademarkRetention;
        this.variationRetention = variationRetention;
        this.personalizationRetention = personalizationRetention;
        this.clock = clock;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public ReportsProperties limits() {
        return properties;
    }

    /**
     * Checks the period and the marketplace of a request.
     *
     * @param marketplace {@code ETSY} or {@code AMAZON} in any case, or null or blank for all
     * @throws InvalidReportPeriodException if a date is missing or malformed, or {@code from} is after {@code to}
     * @throws ReportPeriodTooLongException if the period is longer than {@code listing-guard.reports.max-days}
     * @throws com.thanhdatpb.listingguard.api.check.UnknownMarketplaceException            for an unknown marketplace
     * @throws com.thanhdatpb.listingguard.api.check.MarketplaceNotYetSupportedException    for a planned one
     */
    public ReportQuery query(String from, String to, String marketplace) {
        return ReportQuery.parse(from, to, canonical(marketplace), properties.maxDays());
    }

    /** The period a caller gets when it names none: the {@code default-days} complete UTC days before today. */
    public ReportQuery defaultQuery(String marketplace) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return new ReportQuery(today.minusDays(properties.defaultDays()), today.minusDays(1), canonical(marketplace));
    }

    /** The report of one client for the period. Read only; costs no unit and needs no plan pack. */
    public HealthReport summary(UUID clientId, ReportQuery query) {
        Instant now = clock.instant();
        Scope scope = new Scope(clientId, query.start(), query.endExclusive(), query.marketplace(), now);
        return snapshot.execute(status -> build(scope, query, now));
    }

    private String canonical(String marketplace) {
        return marketplace == null || marketplace.isBlank() ? null : normalizers.get(marketplace).marketplace();
    }

    private HealthReport build(Scope scope, ReportQuery query, Instant now) {
        List<ListingRow> listing = queries.listingByWeek(scope);
        List<SeverityRow> listingSeverity = queries.listingSeverityByWeek(scope);
        List<TrademarkRow> trademark = queries.trademarkByWeek(scope);
        List<OutcomeRow> variation = queries.variationByWeek(scope);
        List<SeverityRow> variationSeverity = queries.variationSeverityByWeek(scope);
        List<ScreeningRow> screening = queries.personalizationByWeek(scope);
        List<SeverityRow> screeningSeverity = queries.personalizationSeverityByWeek(scope);

        PacksView packs = new PacksView(
                listingView(listing, listingSeverity, queries.readinessVersions(scope),
                        rules(queries.listingTopRules(scope, properties.topRules()), properties.topRules()), true),
                trademarkView(trademark, marks(queries.trademarkTopMarks(scope, properties.topMarks()), properties.topMarks()), true),
                variationView(variation, variationSeverity,
                        rules(queries.variationTopRules(scope, properties.topRules()), properties.topRules()), true),
                personalizationView(screening, screeningSeverity,
                        rules(queries.personalizationTopRules(scope, properties.topRules()), properties.topRules()), true));

        Map<LocalDate, List<ListingRow>> listingWeeks = byWeek(listing, ListingRow::week);
        Map<LocalDate, List<SeverityRow>> listingSeverityWeeks = byWeek(listingSeverity, SeverityRow::week);
        Map<LocalDate, List<TrademarkRow>> trademarkWeeks = byWeek(trademark, TrademarkRow::week);
        Map<LocalDate, List<OutcomeRow>> variationWeeks = byWeek(variation, OutcomeRow::week);
        Map<LocalDate, List<SeverityRow>> variationSeverityWeeks = byWeek(variationSeverity, SeverityRow::week);
        Map<LocalDate, List<ScreeningRow>> screeningWeeks = byWeek(screening, ScreeningRow::week);
        Map<LocalDate, List<SeverityRow>> screeningSeverityWeeks = byWeek(screeningSeverity, SeverityRow::week);

        List<WeekView> weeks = new ArrayList<>();
        for (LocalDate monday : weekStarts(query)) {
            LocalDate sunday = monday.plusDays(6);
            weeks.add(new WeekView(isoWeek(monday), monday.isBefore(query.from()) ? query.from() : monday,
                    sunday.isAfter(query.to()) ? query.to() : sunday, monday.isBefore(query.from()) || sunday.isAfter(query.to()),
                    listingView(listingWeeks.getOrDefault(monday, List.of()), listingSeverityWeeks.getOrDefault(monday, List.of()),
                            null, null, false),
                    trademarkView(trademarkWeeks.getOrDefault(monday, List.of()), null, false),
                    variationView(variationWeeks.getOrDefault(monday, List.of()),
                            variationSeverityWeeks.getOrDefault(monday, List.of()), null, false),
                    personalizationView(screeningWeeks.getOrDefault(monday, List.of()),
                            screeningSeverityWeeks.getOrDefault(monday, List.of()), null, false)));
        }

        HealthReport.CoverageView coverage = ReportCoverage.of(now, query.from(), List.of(
                new ReportCoverage.Retained(Pack.TRADEMARK_RISK.id(), trademarkRetention.period()),
                new ReportCoverage.Retained(Pack.VARIATION_CHECKS.id(), variationRetention.period()),
                new ReportCoverage.Retained(Pack.PERSONALIZATION_SCREENING.id(), personalizationRetention.period())));

        return new HealthReport(new PeriodView(query.from(), query.to(), query.days()), query.marketplace(),
                now.truncatedTo(ChronoUnit.SECONDS), versions(query), packs, List.copyOf(weeks), coverage);
    }

    // ---- the pack views. period is true for the totals (pack id and ranked lists present) and false for a week.

    private static ListingRulesView listingView(List<ListingRow> rows, List<SeverityRow> severities, List<String> readinessVersions,
                                                RuleRankingView top, boolean period) {
        long checks = sum(rows, ListingRow::checks);
        long scored = sum(rows, ListingRow::scored);
        long incomplete = sum(rows, ListingRow::incomplete);
        long ready = sum(rows, ListingRow::ready);
        long withReadiness = scored + incomplete;
        ReadinessSummaryView readiness = new ReadinessSummaryView(withReadiness, scored, incomplete, ready,
                withReadiness == 0 ? null : ratio(ready, withReadiness, 4),
                scored == 0 ? null : ratio(sum(rows, ListingRow::scoreTotal), scored, 1), checks - withReadiness,
                readinessVersions);
        return new ListingRulesView(period ? Pack.LISTING_RULES.id() : null, checks,
                counts(rows, ListingRow::status, ListingRow::checks, LISTING_STATUSES), severityViews(severities), readiness, top);
    }

    private static TrademarkRiskView trademarkView(List<TrademarkRow> rows, MarkRankingView top, boolean period) {
        return new TrademarkRiskView(period ? Pack.TRADEMARK_RISK.id() : null, sum(rows, TrademarkRow::checks),
                counts(rows, TrademarkRow::completeness, TrademarkRow::checks, COMPLETENESS),
                counts(rows, TrademarkRow::riskLevel, TrademarkRow::checks, RISK_LEVELS), top);
    }

    private static VariationChecksView variationView(List<OutcomeRow> rows, List<SeverityRow> severities, RuleRankingView top,
                                                     boolean period) {
        return new VariationChecksView(period ? Pack.VARIATION_CHECKS.id() : null, sum(rows, OutcomeRow::checks),
                counts(rows, OutcomeRow::completeness, OutcomeRow::checks, COMPLETENESS), severityViews(severities), top);
    }

    private static PersonalizationView personalizationView(List<ScreeningRow> rows, List<SeverityRow> severities,
                                                           RuleRankingView top, boolean period) {
        return new PersonalizationView(period ? Pack.PERSONALIZATION_SCREENING.id() : null, sum(rows, ScreeningRow::checks),
                counts(rows, ScreeningRow::completeness, ScreeningRow::checks, COMPLETENESS),
                counts(rows, ScreeningRow::trademarkStatus, ScreeningRow::checks, TRADEMARK_STATUSES),
                counts(rows, ScreeningRow::riskLevel, ScreeningRow::checks, RISK_LEVELS), severityViews(severities), top);
    }

    private static RuleRankingView rules(List<RuleRow> rows, int limit) {
        return new RuleRankingView(limit, rows.isEmpty() ? 0 : rows.get(0).distinct(),
                rows.stream().map(row -> new RuleCountView(row.ruleId(), row.severity(), row.findings(), row.checks())).toList());
    }

    private static MarkRankingView marks(List<MarkRow> rows, int limit) {
        return new MarkRankingView(limit, rows.isEmpty() ? 0 : rows.get(0).distinct(),
                rows.stream().map(row -> new MarkCountView(row.mark(), row.findings(), row.checks(), riskOf(row.highestRank())))
                        .toList());
    }

    private static String riskOf(int rank) {
        return switch (rank) {
            case 3 -> "HIGH";
            case 2 -> "MEDIUM";
            default -> "LOW";
        };
    }

    /** The three severities, always, in the order of the enum; a severity with no finding is zero. */
    private static List<SeverityCountView> severityViews(List<SeverityRow> rows) {
        List<SeverityCountView> views = new ArrayList<>();
        for (String severity : SEVERITIES) {
            long findings = 0;
            long checks = 0;
            for (SeverityRow row : rows) {
                if (row.severity().equals(severity)) {
                    findings += row.findings();
                    checks += row.checks();
                }
            }
            views.add(new SeverityCountView(severity, findings, checks));
        }
        return List.copyOf(views);
    }

    // ---- versions

    private VersionsView versions(ReportQuery query) {
        List<String> markets = query.marketplace() == null ? normalizers.supported() : List.of(query.marketplace());
        List<RulesetView> list = new ArrayList<>();
        for (String market : markets) {
            rulesets.find(Pack.LISTING_RULES.id(), market)
                    .ifPresent(active -> list.add(new RulesetView(Pack.LISTING_RULES.id(), market, active.config().version())));
        }
        for (String market : markets) {
            if (serves(trademarkProperties.marketplaces(), market)) {
                list.add(new RulesetView(Pack.TRADEMARK_RISK.id(), market, trademarkRulesets.ruleset().version()));
            }
        }
        for (String market : markets) {
            if (serves(variationProperties.marketplaces(), market)) {
                list.add(new RulesetView(Pack.VARIATION_CHECKS.id(), market, variationRulesets.ruleset().version()));
            }
        }
        for (String market : markets) {
            if (serves(personalizationProperties.marketplaces(), market)) {
                list.add(new RulesetView(Pack.PERSONALIZATION_SCREENING.id(), market, personalizationRulesets.ruleset().version()));
            }
        }
        TrademarkCatalogRuntime.Status status = trademarkRuntime.status();
        return new VersionsView(List.copyOf(list), new CatalogView(status.state().name(),
                status.state() == TrademarkCatalogRuntime.State.READY ? status.detail() : null));
    }

    private static boolean serves(List<String> configured, String market) {
        return configured.stream().anyMatch(code -> code.trim().equalsIgnoreCase(market));
    }

    // ---- weeks

    /** The Mondays of the ISO weeks that touch the period, in order. */
    private static List<LocalDate> weekStarts(ReportQuery query) {
        List<LocalDate> weeks = new ArrayList<>();
        for (LocalDate monday = query.from().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
             !monday.isAfter(query.to()); monday = monday.plusWeeks(1)) {
            weeks.add(monday);
        }
        return weeks;
    }

    /** {@code 2026-W45}; the week after 2026-12-27 is {@code 2026-W53}. */
    static String isoWeek(LocalDate monday) {
        return monday.get(IsoFields.WEEK_BASED_YEAR) + "-W"
                + String.format(Locale.ROOT, "%02d", monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private static <R> Map<LocalDate, List<R>> byWeek(List<R> rows, Function<R, LocalDate> week) {
        return rows.stream().collect(Collectors.groupingBy(week));
    }

    // ---- arithmetic

    private static <R> long sum(List<R> rows, ToLongFunction<R> value) {
        long total = 0;
        for (R row : rows) {
            total += value.applyAsLong(row);
        }
        return total;
    }

    /** One entry per fixed key (zero when absent), in that order, then any other value found, sorted: nothing is lost. */
    private static <R> Map<String, Long> counts(List<R> rows, Function<R, String> key, ToLongFunction<R> value, List<String> fixed) {
        Map<String, Long> out = new LinkedHashMap<>();
        fixed.forEach(name -> out.put(name, 0L));
        Map<String, Long> other = new TreeMap<>();
        for (R row : rows) {
            String name = key.apply(row);
            (out.containsKey(name) ? out : other).merge(name, value.applyAsLong(row), Long::sum);
        }
        out.putAll(other);
        return out;
    }

    private static double ratio(long numerator, long denominator, int scale) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP).doubleValue();
    }
}
