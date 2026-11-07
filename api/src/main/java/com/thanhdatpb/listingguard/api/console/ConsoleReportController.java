package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.check.MarketplaceNotYetSupportedException;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.check.UnknownMarketplaceException;
import com.thanhdatpb.listingguard.api.report.HealthReportService;
import com.thanhdatpb.listingguard.api.report.InvalidReportPeriodException;
import com.thanhdatpb.listingguard.api.report.ReportPeriodTooLongException;
import com.thanhdatpb.listingguard.api.report.ReportQuery;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import jakarta.servlet.http.HttpServletResponse;
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

    public ConsoleReportController(HealthReportService service, NormalizerRegistry normalizers, TrademarkRulesets trademarkRulesets) {
        this.service = service;
        this.normalizers = normalizers;
        this.trademarkRulesets = trademarkRulesets;
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
            model.addAttribute("report", service.summary(user.clientId(), query));
        } catch (InvalidReportPeriodException | ReportPeriodTooLongException | UnknownMarketplaceException
                 | MarketplaceNotYetSupportedException e) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("from", echo(from));
            model.addAttribute("to", echo(to));
            model.addAttribute("marketplaceSelected", "");
            model.addAttribute("error", e.getMessage());
        }
        return VIEW;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static String echo(String text) {
        return text == null || text.length() > ECHO_LIMIT ? "" : text;
    }
}
