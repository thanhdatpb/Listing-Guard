package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.capabilities.CapabilitiesService;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditService;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
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
    private final ConsoleAccountRoles roles;
    private final RoleHomeService roleHome;

    public ConsoleDashboardController(PlanService plans, ConsoleHomeService home, ReauditService reaudit,
                                      CapabilitiesService capabilities, ConsolePilot pilot, ConsoleAccountRoles roles,
                                      RoleHomeService roleHome) {
        this.roles = roles;
        this.roleHome = roleHome;
        this.plans = plans;
        this.home = home;
        this.reaudit = reaudit;
        this.capabilities = capabilities;
        this.pilot = pilot;
    }

    @GetMapping({"/console", "/console/"})
    public String dashboard(@AuthenticationPrincipal ConsoleUser user, @RequestParam(name = "days", required = false) String days,
                            Model model, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (pilot.roleHome()) {
            var account = roles.account(user).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
            model.addAttribute("pilotCapabilities", capabilities.forConsole(user.clientId()));
            if (account.getRole() == ConsoleRole.ADMIN) {
                int period = switch (days == null ? "30" : days) {
                    case "7" -> 7;
                    case "90" -> 90;
                    default -> 30;
                };
                model.addAttribute("overview", roleHome.overview(user.clientId(), roleHome.period(period)));
                model.addAttribute("periods", java.util.List.of(7, 30, 90));
                return "console/manager-home";
            }
            model.addAttribute("work", roleHome.work(user.clientId(), account.getId()));
            return "console/member-home";
        }
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
    @PostMapping("/console/home/hide-guide")
    public String hideGuide(@AuthenticationPrincipal ConsoleUser user) {
        if (!pilot.roleHome()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var account = roles.account(user).filter(row -> row.getRole() == ConsoleRole.MEMBER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        roleHome.hideGuide(user.clientId(), account.getId());
        return "redirect:/console/";
    }
}
