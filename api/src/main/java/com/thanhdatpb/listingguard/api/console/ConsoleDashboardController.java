package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.plan.PlanService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The console home page (ADR-034): what the tool did this week for the signed-in account's client (listings checked, held back by an
 * ERROR, trademark holds by source, the reported false-positive rate), an eight-week chart, the rules most often marked false
 * positive, shortcuts, and the plan and usage at the foot. With no data yet it shows how to start instead of zeros.
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleDashboardController {

    private final PlanService plans;
    private final ConsoleHomeService home;

    public ConsoleDashboardController(PlanService plans, ConsoleHomeService home) {
        this.plans = plans;
        this.home = home;
    }

    @GetMapping({"/console", "/console/"})
    public String dashboard(@AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        model.addAttribute("email", user.email());
        model.addAttribute("usage", plans.usage(user.clientId()));
        model.addAttribute("home", home.home(user.clientId()));
        return "console/dashboard";
    }
}
