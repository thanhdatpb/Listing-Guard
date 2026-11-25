package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.check.MarketplaceNotYetSupportedException;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.check.UnknownMarketplaceException;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStats;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsService;
import com.thanhdatpb.listingguard.api.report.InvalidReportPeriodException;
import com.thanhdatpb.listingguard.api.report.ReportPeriodTooLongException;
import com.thanhdatpb.listingguard.api.report.ReportQuery;
import com.thanhdatpb.listingguard.api.rewrite.RewriteRunStore;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The "Statistics" page of the console (ADR-034) and its CSV: verdicts per rule (with the findings to compare with) and per reviewer,
 * for the signed-in account's client, from {@link FeedbackStatsService} like the endpoint. A period the report refuses is a
 * {@code 400} page here too. It reads only; it shows no note and no text of a listing. Reviewers are named by e-mail or key label:
 * the reader is a member of the same client.
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleStatsController {

    static final String VIEW = "console/stats";

    /** The longest date text echoed back into the form after a refusal. */
    private static final int ECHO_LIMIT = 32;

    private final FeedbackStatsService stats;
    private final NormalizerRegistry normalizers;
    private final ConsoleMessages messages;
    private final ObjectProvider<RewriteRunStore> rewriteRuns;

    public ConsoleStatsController(FeedbackStatsService stats, NormalizerRegistry normalizers, ConsoleMessages messages,
                                  ObjectProvider<RewriteRunStore> rewriteRuns) {
        this.stats = stats;
        this.normalizers = normalizers;
        this.messages = messages;
        this.rewriteRuns = rewriteRuns;
    }

    @GetMapping("/console/stats")
    public String page(@AuthenticationPrincipal ConsoleUser user,
                       @RequestParam(name = "from", required = false) String from,
                       @RequestParam(name = "to", required = false) String to,
                       @RequestParam(name = "marketplace", required = false) String marketplace,
                       Model model, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        model.addAttribute("email", user.email());
        model.addAttribute("marketplaces", normalizers.supported());
        model.addAttribute("maxDays", stats.maxDays());
        try {
            ReportQuery query = query(from, to, marketplace);
            model.addAttribute("from", query.from().toString());
            model.addAttribute("to", query.to().toString());
            model.addAttribute("marketplaceSelected", query.marketplace() == null ? "" : query.marketplace());
            model.addAttribute("stats", stats.stats(user.clientId(), query));
            // the use of the rewrite suggestions (ADR-037): only while that feature is on, for the same period
            RewriteRunStore rewrite = rewriteRuns.getIfAvailable();
            if (rewrite != null) {
                model.addAttribute("rewriteUsage", rewrite.usage(user.clientId(), query.start(), query.endExclusive()));
            }
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

    /** The same numbers as a CSV file, for a report to a manager. A refused period is a {@code 400} with the reason as text. */
    @GetMapping("/console/stats.csv")
    public ResponseEntity<byte[]> csv(@AuthenticationPrincipal ConsoleUser user,
                                      @RequestParam(name = "from", required = false) String from,
                                      @RequestParam(name = "to", required = false) String to,
                                      @RequestParam(name = "marketplace", required = false) String marketplace) {
        try {
            ReportQuery query = query(from, to, marketplace);
            FeedbackStats result = stats.stats(user.clientId(), query);
            String name = "listing-guard-feedback-" + query.from() + "-" + query.to() + ".csv";
            return ResponseEntity.ok()
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                    .body(StatsCsv.csv(messages, result).getBytes(StandardCharsets.UTF_8));
        } catch (InvalidReportPeriodException | ReportPeriodTooLongException | UnknownMarketplaceException
                 | MarketplaceNotYetSupportedException e) {
            return ResponseEntity.badRequest().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(problem(e).getBytes(StandardCharsets.UTF_8));
        }
    }

    ReportQuery query(String from, String to, String marketplace) {
        boolean named = !blank(from) || !blank(to);
        return named ? stats.query(from, to, marketplace) : stats.defaultQuery(marketplace);
    }

    /** What a refusal says, in the language of the page, with the report's messages (the same rules refuse the same periods). */
    String problem(RuntimeException e) {
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

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static String echo(String text) {
        return text == null || text.length() > ECHO_LIMIT ? "" : text;
    }
}
