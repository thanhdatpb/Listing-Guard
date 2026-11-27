package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.capabilities.CapabilitiesService;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditService;
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
    private final ReauditService reaudit;
    private final CapabilitiesService capabilities;
    private final ConsolePilot pilot;

    public ConsoleDashboardController(PlanService plans, ConsoleHomeService home, ReauditService reaudit,
                                      CapabilitiesService capabilities, ConsolePilot pilot) {
        this.plans = plans;
        this.home = home;
        this.reaudit = reaudit;
        this.capabilities = capabilities;
        this.pilot = pilot;
    }

    @GetMapping({"/console", "/console/"})
    public String dashboard(@AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        model.addAttribute("email", user.email());
        model.addAttribute("usage", plans.usage(user.clientId()));
        model.addAttribute("home", home.home(user.clientId()));
        if (pilot.enabled()) {
            model.addAttribute("pilotCapabilities", capabilities.forConsole(user.clientId()));
        }
        // the catalog changed since a scope was last re-audited (ADR-035)
        model.addAttribute("reauditNotice", reaudit.notice(user.clientId()).orElse(null));
        return "console/dashboard";
    }
}
