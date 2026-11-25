package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * "Giải thích kết quả" (ADR-053): the page that the result pages of the bulk check and the editor link to from their one short line. It
 * holds the sentences those pages used to repeat (what "OK" means, what a content check leaves out, what the readiness counts) and the
 * trademark disclaimer of the active ruleset, printed unchanged. It shows the same text to every signed-in account, reads no listing
 * and stores nothing.
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleExplainController {

    private final TrademarkRulesets rulesets;

    public ConsoleExplainController(TrademarkRulesets rulesets) {
        this.rulesets = rulesets;
    }

    @GetMapping("/console/explain")
    public String page(@AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        model.addAttribute("email", user.email());
        model.addAttribute("disclaimer", rulesets.ruleset().disclaimer());
        return "console/explain";
    }
}
