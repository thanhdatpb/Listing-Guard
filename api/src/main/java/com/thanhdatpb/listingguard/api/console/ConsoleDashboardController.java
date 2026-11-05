package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.plan.PlanService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleDashboardController {

    private final PlanService plans;

    public ConsoleDashboardController(PlanService plans) {
        this.plans = plans;
    }

    @GetMapping({"/console", "/console/"})
    public String dashboard(@AuthenticationPrincipal ConsoleUser user, Model model) {
        model.addAttribute("email", user.email());
        model.addAttribute("usage", plans.usage(user.clientId()));
        return "console/dashboard";
    }
}
