package com.thanhdatpb.listingguard.api.console.editor;

import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.ruleset.RulesetLimitsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
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

    private static final Pattern FIX_ID = Pattern.compile("\\d{1,4}");

    private final PlanService plans;
    private final RulesetLimitsService rulesetLimits;
    private final EditorCheckService checks;

    public ConsoleEditorController(PlanService plans, RulesetLimitsService rulesetLimits, EditorCheckService checks) {
        this.plans = plans;
        this.rulesetLimits = rulesetLimits;
        this.checks = checks;
    }

    @GetMapping("/console/check")
    public String form(@RequestParam(name = "marketplace", defaultValue = "ETSY") String marketplace,
                       @AuthenticationPrincipal ConsoleUser user, Model model) {
        EditorMarketplace selected = marketplace(marketplace);
        populate(model, user, selected, EditorForm.empty(selected), Map.of());
        return VIEW;
    }

    /**
     * One press of "Check". A price or quantity that cannot be read is sent back to its field and nothing is checked (and
     * nothing is counted). Otherwise both checks run through {@link EditorCheckService}; the form comes back as it was
     * typed, next to the result.
     */
    @PostMapping("/console/check")
    public String check(@ModelAttribute("form") EditorForm form, @AuthenticationPrincipal ConsoleUser user, Model model) {
        EditorMarketplace selected = marketplace(form.marketplace());
        EditorListingMapper.Mapped mapped = EditorListingMapper.map(selected, form);
        if (!mapped.valid()) {
            populate(model, user, selected, form, mapped.errors());
            return VIEW;
        }
        EditorResult result = checks.check(user.clientId(), selected, mapped.listing());
        populate(model, user, selected, form, Map.of());
        model.addAttribute("result", result);
        model.addAttribute("otherOpen", result.requiredFieldsFinding() || form.hasOtherFields());
        return VIEW;
    }

    /**
     * "Apply suggestion": puts the proposed value of one fix into the form and shows the form again. It never runs a check, so
     * it costs nothing and the old result is not shown beside the changed text; the seller presses "Check" when ready. The
     * button belongs to the editor form (HTML {@code form} attribute), so what the seller has typed since the check is what
     * comes back, and the fix is described by hidden values named {@code fix.<id>.field|value|action|proposed}.
     */
    @PostMapping("/console/check/apply")
    public String apply(@ModelAttribute("form") EditorForm form, @RequestParam(name = "apply", defaultValue = "") String fixId,
                        HttpServletRequest request, @AuthenticationPrincipal ConsoleUser user, Model model) {
        EditorMarketplace selected = marketplace(form.marketplace());
        EditorFixApplier.Outcome outcome;
        if (FIX_ID.matcher(fixId).matches()) {
            String prefix = "fix." + fixId + ".";
            outcome = EditorFixApplier.apply(form, request.getParameter(prefix + "field"), request.getParameter(prefix + "value"),
                    request.getParameter(prefix + "action"), request.getParameter(prefix + "proposed"));
        } else {
            outcome = EditorFixApplier.apply(form, null, null, null, null);
        }
        populate(model, user, selected, outcome.form(), Map.of());
        model.addAttribute("notice", outcome.message());
        model.addAttribute("noticeApplied", outcome.applied());
        return VIEW;
    }

    /** Everything the page needs besides a result: the form, the counters' limits, the cost of a press and the field errors. */
    void populate(Model model, ConsoleUser user, EditorMarketplace marketplace, EditorForm form, Map<String, String> errors) {
        model.addAttribute("email", user.email());
        model.addAttribute("marketplace", marketplace);
        model.addAttribute("marketplaces", EditorMarketplace.values());
        model.addAttribute("form", form);
        EditorLimits limits = EditorLimits.of(rulesetLimits.limits(marketplace.code(), form.productType()).limits());
        model.addAttribute("limits", limits);
        model.addAttribute("preview", EditorPreview.of(form, limits.titleMax()));
        model.addAttribute("cost", EditorCost.of(plans.usage(user.clientId())));
        model.addAttribute("fieldErrors", errors);
        // collapsed by default; open when a field could not be read, or when it already holds what the seller typed
        model.addAttribute("otherOpen", !errors.isEmpty() || form.hasOtherFields());
    }

    static EditorMarketplace marketplace(String code) {
        return EditorMarketplace.parse(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown marketplace."));
    }
}
