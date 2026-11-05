package com.thanhdatpb.listingguard.api.console.editor;

import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The "Check listing" page of the console (ADR-014, part B3). The seller types a listing, and the page shows what the rules
 * found, a verified value for the fixes that have one, the readiness score and the trademark result, side by side.
 *
 * <p>It only checks and suggests: nothing is published, nothing is written to a marketplace, and the listing the seller types is
 * never stored (it lives in the request and in the page that comes back).
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleEditorController {

    static final String VIEW = "console/editor";

    private final PlanService plans;
    private final ActiveRulesets rulesets;

    public ConsoleEditorController(PlanService plans, ActiveRulesets rulesets) {
        this.plans = plans;
        this.rulesets = rulesets;
    }

    @GetMapping("/console/check")
    public String form(@RequestParam(name = "marketplace", defaultValue = "ETSY") String marketplace,
                       @AuthenticationPrincipal ConsoleUser user, Model model) {
        EditorMarketplace selected = marketplace(marketplace);
        populate(model, user, selected, EditorForm.empty(selected), Map.of());
        return VIEW;
    }

    /** Everything the page needs besides a result: the form, the counters' limits, the cost of a press and the field errors. */
    void populate(Model model, ConsoleUser user, EditorMarketplace marketplace, EditorForm form, Map<String, String> errors) {
        RulesetConfig config = rulesets.get(marketplace.code()).config();
        model.addAttribute("email", user.email());
        model.addAttribute("marketplace", marketplace);
        model.addAttribute("marketplaces", EditorMarketplace.values());
        model.addAttribute("form", form);
        model.addAttribute("limits", EditorLimits.of(config, marketplace, form.productType()));
        model.addAttribute("cost", EditorCost.of(plans.usage(user.clientId())));
        model.addAttribute("fieldErrors", errors);
        model.addAttribute("otherOpen", !errors.isEmpty());
    }

    static EditorMarketplace marketplace(String code) {
        return EditorMarketplace.parse(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown marketplace."));
    }
}
