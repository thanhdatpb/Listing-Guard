package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.check.CheckScope;
import com.thanhdatpb.listingguard.api.check.MarketplaceNotYetSupportedException;
import com.thanhdatpb.listingguard.api.check.UnknownMarketplaceException;
import com.thanhdatpb.listingguard.api.feedback.FeedbackStatsService;
import com.thanhdatpb.listingguard.api.report.InvalidReportPeriodException;
import com.thanhdatpb.listingguard.api.report.ReportPeriodTooLongException;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.console.pilot:false}")
public class PilotReportController {
    private final ConsoleStatsController statistics;
    private final FeedbackStatsService feedback;
    private final PilotMetrics metrics;
    private final ConsoleMessages messages;
    private final ConsolePilot pilot;
    private final RoleHomeService roleHome;
    private final ObjectProvider<PhraseConsoleMetrics> phraseMetrics;

    public PilotReportController(ConsoleStatsController statistics, FeedbackStatsService feedback,
                                 PilotMetrics metrics, ConsoleMessages messages, ObjectProvider<PhraseConsoleMetrics> phraseMetrics,
                                 ConsolePilot pilot, RoleHomeService roleHome) {
        this.pilot = pilot;
        this.roleHome = roleHome;
        this.phraseMetrics = phraseMetrics;
        this.statistics = statistics;
        this.feedback = feedback;
        this.metrics = metrics;
        this.messages = messages;
    }

    @GetMapping({"/console/stats/pilot", "/console/stats/pilot.csv"})
    public String report(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                         @RequestParam(required = false) String marketplace, @AuthenticationPrincipal ConsoleUser user,
                         Model model, jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        try {
            var query = statistics.query(from, to, marketplace);
            boolean csvRequest = request.getServletPath().endsWith(".csv") || request.getRequestURI().endsWith(".csv");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            if (pilot.roleHome() && !csvRequest) {
                model.addAttribute("overview", roleHome.overview(user.clientId(), query));
                return "console/pilot-report";
            }
            var days = metrics.days(user.clientId(), query);
            // checks made in content scope (ADR-052) are counted apart and shown only when the period has some: with none, the report
            // is exactly what it was
            var contentDays = metrics.days(user.clientId(), query, CheckScope.Kind.CONTENT);
            boolean hasContent = contentDays.stream().anyMatch(day -> day.checked() > 0);
            var rules = feedback.stats(user.clientId(), query).byRule().stream().filter(rule -> rule.verdicts() > 0).toList();
            var phrases = phraseMetrics.getIfAvailable();
            var phraseWeeks = phrases == null ? null : phrases.weeks(user.clientId(), query);
            if (!csvRequest) {
                model.addAttribute("pilotDays", days);
                model.addAttribute("phraseWeeks", phraseWeeks);
                model.addAttribute("pilotContentDays", hasContent ? contentDays : null);
                model.addAttribute("pilotRules", rules);
                model.addAttribute("pilotQuery", query);
                return "console/pilot-report";
            }
            StringBuilder csv = new StringBuilder("\ufeff");
            StatsCsv.line(csv, messages.get("pilot.report"), query.from().toString(), query.to().toString(),
                    query.marketplace() == null ? messages.get("report.form.all") : query.marketplace());
            StatsCsv.line(csv, messages.get("pilot.day"), messages.get("pilot.checked"),
                    "HIGH", messages.get("pilot.share"), "MEDIUM", messages.get("pilot.share"), "LOW", messages.get("pilot.share"),
                    messages.get("pilot.unranked"), messages.get("pilot.rechecked"));
            for (var day : days) {
                csvDay(csv, day);
            }
            if (hasContent) {
                StatsCsv.line(csv, messages.get("pilot.daily.content"));
                for (var day : contentDays) {
                    csvDay(csv, day);
                }
            }
            StatsCsv.line(csv, messages.get("report.col.pack"), messages.get("report.col.rule"), messages.get("stats.col.source"),
                    messages.get("stats.col.verdicts"), messages.get("feedback.verdict.false-positive"), messages.get("stats.col.rate"));
            for (var rule : rules) {
                StatsCsv.line(csv, rule.pack(), rule.ruleId(), rule.source(), Long.toString(rule.verdicts()),
                        Long.toString(rule.falsePositive()), StatsCsv.rate(rule.falsePositiveRate()));
            }
            if (phraseWeeks != null) {
                PhraseConsoleMetrics.appendCsv(csv, messages, phraseWeeks);
            }
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8).toString());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename("listing-guard-pilot-" + query.from() + "-" + query.to() + ".csv").build().toString());
            response.getWriter().write(csv.toString());
            return null;
        } catch (InvalidReportPeriodException | ReportPeriodTooLongException | UnknownMarketplaceException
                 | MarketplaceNotYetSupportedException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, statistics.problem(e), e);
        }
    }

    private static void csvDay(StringBuilder csv, PilotMetrics.Day day) {
        StatsCsv.line(csv, day.date().toString(), Long.toString(day.checked()),
                Long.toString(day.high()), StatsCsv.rate(day.rate(day.high())),
                Long.toString(day.medium()), StatsCsv.rate(day.rate(day.medium())),
                Long.toString(day.low()), StatsCsv.rate(day.rate(day.low())),
                Long.toString(day.unranked()), Long.toString(day.rechecked()));
    }
}
