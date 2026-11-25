package com.thanhdatpb.listingguard.api.console;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The "Sẵn sàng" page (ADR-053): whether the pilot can be handed to a seller. It exists only while {@code listing-guard.console.pilot}
 * is true (without it the path is a 404 like every other page of the pilot's own), and {@link ConsolePilot} answers a signed-in
 * account that is not an ADMIN of its client with a 403. It reads and shows; it writes nothing.
 */
@Controller
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.console.pilot:false}")
public class ConsoleReadinessController {

    private final PilotReadiness readiness;

    public ConsoleReadinessController(PilotReadiness readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/console/readiness")
    public String page(@AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        // the page names the catalog version and the accounts' counts: nothing may keep a copy
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        model.addAttribute("email", user.email());
        model.addAttribute("view", readiness.view(user.clientId()));
        return "console/readiness";
    }
}
