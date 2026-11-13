package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.check.MarketplaceNotYetSupportedException;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.check.UnknownMarketplaceException;
import com.thanhdatpb.listingguard.api.report.HealthReport;
import com.thanhdatpb.listingguard.api.report.HealthReportService;
import com.thanhdatpb.listingguard.api.report.InvalidReportPeriodException;
import com.thanhdatpb.listingguard.api.report.ReportPeriodTooLongException;
import com.thanhdatpb.listingguard.api.report.ReportQuery;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The "Report" page of the console (ADR-018): the listing health report of the signed-in account's client, from the same
 * {@link HealthReportService} as {@code GET /api/v1/reports/summary} and the {@code health-report} job, so the three show the same
 * numbers. Read only: a plain {@code GET} form, nothing is stored, no plan pack is needed and no unit is charged. The client is
 * the account's own ({@link ConsoleUser#clientId()}); there is no parameter that names another.
 *
 * <p>With the console off this controller does not exist, so there is no route. A period or marketplace that cannot be used is
 * a {@code 400} page with the message, not an error page. The page shows only what the report holds: counts, enumerations, rule
 * ids and catalog marks, all rendered with {@code th:text}.
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleReportController {

    static final String VIEW = "console/report";

    /** The longest date text echoed back into the form after a refusal. */
    private static final int ECHO_LIMIT = 32;

    private final HealthReportService service;
    private final NormalizerRegistry normalizers;
    private final TrademarkRulesets trademarkRulesets;
    private final ConsoleMessages messages;

    public ConsoleReportController(HealthReportService service, NormalizerRegistry normalizers, TrademarkRulesets trademarkRulesets,
                                   ConsoleMessages messages) {
        this.service = service;
        this.normalizers = normalizers;
        this.trademarkRulesets = trademarkRulesets;
        this.messages = messages;
    }

    @GetMapping("/console/report")
    public String report(@AuthenticationPrincipal ConsoleUser user,
                         @RequestParam(name = "from", required = false) String from,
                         @RequestParam(name = "to", required = false) String to,
                         @RequestParam(name = "marketplace", required = false) String marketplace,
                         Model model, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        model.addAttribute("email", user.email());
        model.addAttribute("marketplaces", normalizers.supported());
        model.addAttribute("maxDays", service.limits().maxDays());
        model.addAttribute("trademarkDisclaimer", trademarkRulesets.ruleset().disclaimer());
        boolean named = !blank(from) || !blank(to);
        try {
            ReportQuery query = named ? service.query(from, to, marketplace) : service.defaultQuery(marketplace);
            model.addAttribute("from", query.from().toString());
            model.addAttribute("to", query.to().toString());
            model.addAttribute("marketplaceSelected", query.marketplace() == null ? "" : query.marketplace());
            HealthReport report = service.summary(user.clientId(), query);
            model.addAttribute("report", report);
            model.addAttribute("coverageText", coverageText(report.coverageNote(), query.from()));
        } catch (InvalidReportPeriodException | ReportPeriodTooLongException | UnknownMarketplaceException
                 | MarketplaceNotYetSupportedException e) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("from", echo(from));
            model.addAttribute("to", echo(to));
            model.addAttribute("marketplaceSelected", "");
            model.addAttribute("error", problem(e));
        }
        return VIEW;
    }

    /**
     * What the refusal says, in the language of the page. The API's own sentence (the exception's message) is English and part of
     * its contract; the console writes its own from what is wrong, and never repeats what the caller sent.
     */
    private String problem(RuntimeException e) {
        if (e instanceof InvalidReportPeriodException period) {
            return switch (period.reason()) {
                case FROM_AFTER_TO -> messages.get("report.error.fromafterto");
                case REQUIRED -> messages.get("report.error.required", period.parameter());
                case NOT_A_DATE -> messages.get("report.error.notadate", period.parameter());
                case NOT_A_CALENDAR_DATE -> messages.get("report.error.notarealdate", period.parameter());
                case OTHER -> messages.get("report.error.period");
            };
        }
        if (e instanceof ReportPeriodTooLongException tooLong) {
            return messages.get("report.error.toolong", tooLong.requestedDays(), tooLong.maxDays());
        }
        String supported = String.join(", ", normalizers.supported());
        return messages.get(e instanceof MarketplaceNotYetSupportedException
                ? "report.error.marketplace.planned" : "report.error.marketplace.unknown", supported);
    }

    /**
     * The coverage note of the report in the language of the page, built from the same figures as the API's English sentence
     * ({@code ReportCoverage}): a pack is incomplete when the period starts before the first day it still has every result of.
     */
    private String coverageText(HealthReport.CoverageView coverage, LocalDate periodStart) {
        List<String> kept = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (HealthReport.RetentionView pack : coverage.retention()) {
            kept.add(messages.get("report.coverage.kept", pack.pack(), pack.period()));
            if (periodStart.isBefore(pack.coveredFrom())) {
                gaps.add(messages.get("report.coverage.gap", pack.pack(), pack.period(), pack.coveredFrom()));
            }
        }
        return gaps.isEmpty()
                ? messages.get("report.coverage.complete", String.join(", ", kept))
                : messages.get("report.coverage.incomplete", String.join(", ", gaps));
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static String echo(String text) {
        return text == null || text.length() > ECHO_LIMIT ? "" : text;
    }
}
