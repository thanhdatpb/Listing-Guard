package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.history.CheckHistoryService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.variation.VariationCheckResult;
import com.thanhdatpb.listingguard.api.variation.VariationCheckService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Account and client scoped history of persisted check results (ADR-033). */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleHistoryController {
    public record Finding(String ruleId, String level, String field, String message, FeedbackTarget target) { }

    private final CheckHistoryService history;
    private final CheckService checks;
    private final TrademarkCheckService trademarks;
    private final VariationCheckService variations;
    private final ConsoleFeedbackSupport feedback;

    public ConsoleHistoryController(CheckHistoryService history, CheckService checks, TrademarkCheckService trademarks,
                                    VariationCheckService variations, ConsoleFeedbackSupport feedback) {
        this.history = history;
        this.checks = checks;
        this.trademarks = trademarks;
        this.variations = variations;
        this.feedback = feedback;
    }

    @GetMapping("/console/history")
    public String page(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                       @RequestParam(defaultValue = "") String status,
                       @RequestParam(defaultValue = "") String marketplace,
                       @RequestParam(defaultValue = "") String checker,
                       @RequestParam(defaultValue = "0") int page,
                       @AuthenticationPrincipal ConsoleUser user, Model model,
                       jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        CheckHistoryService.Query query = new CheckHistoryService.Query(from, to, status, marketplace, checker, page);
        model.addAttribute("email", user.email());
        model.addAttribute("history", history.page(user.clientId(), query));
        model.addAttribute("from", from == null ? "" : from.toString());
        model.addAttribute("to", to == null ? "" : to.toString());
        model.addAttribute("status", status);
        model.addAttribute("marketplace", marketplace);
        model.addAttribute("checker", checker);
        return "console/history";
    }

    @GetMapping("/console/history/{pack}/{id}")
    public String open(@PathVariable String pack, @PathVariable UUID id, @AuthenticationPrincipal ConsoleUser user,
                       Model model, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        List<Finding> findings = new ArrayList<>();
        String marketplace;
        String status;
        if ("listing-rules".equals(pack)) {
            CheckResult result = checks.get(user.clientId(), id);
            marketplace = result.marketplace();
            status = result.status().name();
            for (int i = 0; i < result.findings().size(); i++) {
                var f = result.findings().get(i);
                findings.add(new Finding(f.ruleId(), f.severity().name(), f.field(), f.message(),
                        FeedbackTarget.listingRules(id, i)));
            }
        } else if ("trademark-risk".equals(pack)) {
            TrademarkCheckResult result = trademarks.get(user.clientId(), id);
            marketplace = result.marketplace();
            status = result.completeness().name();
            for (int i = 0; i < result.findings().size(); i++) {
                var f = result.findings().get(i);
                findings.add(new Finding(f.ruleId(), f.risk().name(), f.field(), f.message(),
                        FeedbackTarget.trademark(id, i)));
            }
        } else if ("variation-checks".equals(pack)) {
            VariationCheckResult result = variations.get(user.clientId(), id);
            marketplace = result.marketplace();
            status = result.completeness().name();
            for (int i = 0; i < result.findings().size(); i++) {
                var f = result.findings().get(i);
                findings.add(new Finding(f.ruleId(), f.severity().name(), f.field(), f.message(),
                        FeedbackTarget.variation(id, i)));
            }
        } else {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        model.addAttribute("email", user.email());
        model.addAttribute("pack", pack);
        model.addAttribute("checkId", id);
        model.addAttribute("marketplace", marketplace);
        model.addAttribute("status", status);
        model.addAttribute("findings", findings);
        feedback.populate(model, user, findings.stream().map(Finding::target).toList());
        return "console/history-result";
    }
}
