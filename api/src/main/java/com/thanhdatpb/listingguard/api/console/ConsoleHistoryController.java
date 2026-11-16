package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.history.CheckHistoryService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.variation.VariationCheckResult;
import com.thanhdatpb.listingguard.api.variation.VariationCheckService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Account and client scoped history of persisted check results (ADR-033, ADR-041). The list is a row of filters (the period, the
 * status, the marketplace, who checked, and a part of a SKU) over a table; a row opens the stored result, whose findings are the
 * cards every other page prints. Nothing of a listing's text is stored, so a stored result has findings and no listing.
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleHistoryController {

    /** The periods the filter offers, in days; a period is counted back from today (UTC), and {@code all} is no limit. */
    static final List<String> PERIODS = List.of("all", "7", "30", "90");

    /**
     * The filter as the page shows it. {@code period} is one of {@link #PERIODS}, or {@code custom} when the address carries its own
     * {@code from} and {@code to} (kept in the form, so a new filter does not drop them).
     */
    public record Filters(String period, String from, String to, String status, String marketplace, String checker, String sku) {

        /** True when anything narrows the list, so the page offers to clear it. */
        public boolean active() {
            return !"all".equals(period) || !status.isEmpty() || !marketplace.isEmpty() || !checker.isEmpty() || !sku.isEmpty();
        }
    }

    private final CheckHistoryService history;
    private final CheckService checks;
    private final TrademarkCheckService trademarks;
    private final VariationCheckService variations;
    private final ConsoleFeedbackSupport feedback;
    private final FindingCards cards;
    private final Clock clock;

    public ConsoleHistoryController(CheckHistoryService history, CheckService checks, TrademarkCheckService trademarks,
                                    VariationCheckService variations, ConsoleFeedbackSupport feedback, FindingCards cards, Clock clock) {
        this.history = history;
        this.checks = checks;
        this.trademarks = trademarks;
        this.variations = variations;
        this.feedback = feedback;
        this.cards = cards;
        this.clock = clock;
    }

    @GetMapping("/console/history")
    public String page(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                       @RequestParam(required = false) String period,
                       @RequestParam(defaultValue = "") String status,
                       @RequestParam(defaultValue = "") String marketplace,
                       @RequestParam(defaultValue = "") String checker,
                       @RequestParam(defaultValue = "") String sku,
                       @RequestParam(defaultValue = "0") int page,
                       @AuthenticationPrincipal ConsoleUser user, Model model,
                       jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String shown = period(period, from, to);
        LocalDate start = from;
        LocalDate end = to;
        if (PERIODS.contains(shown)) {
            start = "all".equals(shown) ? null : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(Long.parseLong(shown));
            end = null;
        }
        CheckHistoryService.Query query = new CheckHistoryService.Query(start, end, status, marketplace, checker, sku, page);
        List<String> checkers = new ArrayList<>(history.checkers(user.clientId()));
        if (!checker.isBlank() && !checkers.contains(checker.strip())) {
            checkers.add(checker.strip());
        }
        model.addAttribute("email", user.email());
        model.addAttribute("history", history.page(user.clientId(), query));
        model.addAttribute("checkers", checkers);
        model.addAttribute("filters", new Filters(shown, "custom".equals(shown) && from != null ? from.toString() : "",
                "custom".equals(shown) && to != null ? to.toString() : "", status.strip().toUpperCase(Locale.ROOT), marketplace.strip(),
                checker.strip(), sku.strip()));
        return "console/history";
    }

    /** {@code custom} when the address has its own dates and no preset period, else the preset ({@code all} when there is none). */
    private static String period(String period, LocalDate from, LocalDate to) {
        if (period != null && PERIODS.contains(period.strip())) {
            return period.strip();
        }
        return from != null || to != null ? "custom" : "all";
    }

    @GetMapping("/console/history/{pack}/{id}")
    public String open(@PathVariable String pack, @PathVariable UUID id, @AuthenticationPrincipal ConsoleUser user,
                       Model model, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        List<FindingCards.Stored> findings = new ArrayList<>();
        List<FeedbackTarget> targets = new ArrayList<>();
        String marketplace;
        String status;
        if ("listing-rules".equals(pack)) {
            CheckResult result = checks.get(user.clientId(), id);
            marketplace = result.marketplace();
            status = result.status().name();
            for (int i = 0; i < result.findings().size(); i++) {
                var f = result.findings().get(i);
                FeedbackTarget target = FeedbackTarget.listingRules(id, i);
                CheckResult.FixView fix = f.fix();
                findings.add(new FindingCards.Stored(f.ruleId(), f.severity().name().toLowerCase(Locale.ROOT), f.field(), f.message(),
                        f.suggestion(), f.value(), null, null,
                        fix == null ? null : new FindingCard.Fix(fix.action().name(), fix.description(), fix.proposedValue()), target));
                targets.add(target);
            }
        } else if ("trademark-risk".equals(pack)) {
            TrademarkCheckResult result = trademarks.get(user.clientId(), id);
            marketplace = result.marketplace();
            status = result.completeness().name();
            for (int i = 0; i < result.findings().size(); i++) {
                var f = result.findings().get(i);
                FeedbackTarget target = FeedbackTarget.trademark(id, i);
                findings.add(new FindingCards.Stored(f.ruleId(), f.risk().name().toLowerCase(Locale.ROOT), f.field(), f.message(),
                        f.suggestion(), f.snippet(), f.mark(), f.source().name(), null, target));
                targets.add(target);
            }
        } else if ("variation-checks".equals(pack)) {
            VariationCheckResult result = variations.get(user.clientId(), id);
            marketplace = result.marketplace();
            status = result.completeness().name();
            for (int i = 0; i < result.findings().size(); i++) {
                var f = result.findings().get(i);
                FeedbackTarget target = FeedbackTarget.variation(id, i);
                findings.add(new FindingCards.Stored(f.ruleId(), f.severity().name().toLowerCase(Locale.ROOT), f.field(), f.message(),
                        f.suggestion(), f.value(), null, null, null, target));
                targets.add(target);
            }
        } else {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        model.addAttribute("email", user.email());
        model.addAttribute("pack", pack);
        model.addAttribute("checkId", id);
        model.addAttribute("marketplace", marketplace);
        model.addAttribute("status", status);
        model.addAttribute("cards", cards.history(pack, marketplace, findings));
        feedback.populate(model, user, targets);
        return "console/history-result";
    }
}
