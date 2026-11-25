package com.thanhdatpb.listingguard.api.console.editor;

import com.thanhdatpb.listingguard.api.console.ConsoleFeedbackSupport;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.PilotMetrics;
import com.thanhdatpb.listingguard.api.console.PilotDraft;
import com.thanhdatpb.listingguard.api.console.ConsolePilot;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.ruleset.RulesetLimitsService;
import com.thanhdatpb.listingguard.ruleengine.readiness.ReadinessScorer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final ConsoleMessages messages;
    private final ConsoleFeedbackSupport feedback;
    private final com.thanhdatpb.listingguard.api.history.CheckHistoryRecorder history;
    private final ReadinessScorer readiness;
    private final PilotMetrics pilotMetrics;
    private final ConsolePilot pilot;
    private final Clock clock;

    public ConsoleEditorController(PlanService plans, RulesetLimitsService rulesetLimits, EditorCheckService checks,
                                   ConsoleMessages messages, ConsoleFeedbackSupport feedback,
                                   com.thanhdatpb.listingguard.api.history.CheckHistoryRecorder history, ReadinessScorer readiness,
                                   PilotMetrics pilotMetrics, ConsolePilot pilot, Clock clock) {
        this.readiness = readiness;
        this.pilotMetrics = pilotMetrics;
        this.pilot = pilot;
        this.clock = clock;
        this.plans = plans;
        this.rulesetLimits = rulesetLimits;
        this.checks = checks;
        this.messages = messages;
        this.feedback = feedback;
        this.history = history;
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
    public String check(@ModelAttribute("form") EditorForm form, @RequestParam(required = false) String pilotDraft,
                        HttpServletRequest request, @AuthenticationPrincipal ConsoleUser user, Model model) {
        if (pilot.enabled()) {
            model.addAttribute("pilotDraft", pilotDraft);
        }
        EditorMarketplace selected = marketplace(form.marketplace());
        EditorListingMapper.Mapped mapped = EditorListingMapper.map(selected, form);
        if (!mapped.valid()) {
            populate(model, user, selected, form, texts(mapped.errors()));
            return VIEW;
        }
        EditorResult result = checks.check(user.clientId(), selected, mapped.listing());
        if (pilot.enabled() && (result.readiness().checkId() != null || result.trademark().checkId() != null)) {
            var draft = PilotDraft.advance(request.getSession(), PilotDraft.Surface.SINGLE, clock, user.clientId(),
                    selected.code(), pilotDraft, Map.of(1, PilotDraft.Fingerprint.of(null, mapped.listing().toString())));
            model.addAttribute("pilotDraft", draft.id());
            String risk = result.trademark().kind() == EditorResult.TrademarkKind.RESULT ? result.trademark().riskLevel() : null;
            pilotMetrics.record(user.clientId(), selected.code(), List.of(new PilotMetrics.Observation(risk, !draft.changed().isEmpty())));
        }
        feedback.accountId(user).ifPresent(account -> {
            if (result.readiness().checkId() != null) {
                history.account(user.clientId(), account, com.thanhdatpb.listingguard.api.plan.Pack.LISTING_RULES.id(),
                        result.readiness().checkId(), selected.code(), 1, null);
            }
            if (result.trademark().checkId() != null) {
                history.account(user.clientId(), account, com.thanhdatpb.listingguard.api.plan.Pack.TRADEMARK_RISK.id(),
                        result.trademark().checkId(), selected.code(), 1, null);
            }
        });
        populate(model, user, selected, form, Map.of());
        model.addAttribute("result", result);
        // the listing as checked gets the limits the form already shows; the readiness is out of the setting of its formula (ADR-014)
        EditorLimits limits = (EditorLimits) model.getAttribute("limits");
        model.addAttribute("listingView", result.listing().withLimits(limits.titleMax(),
                selected.isEtsy() ? limits.tagCountMax() : limits.bulletCountMax()));
        model.addAttribute("readinessMax", readiness.config().baseScore());
        feedback.populate(model, user, result.targets());
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
            outcome = EditorFixApplier.apply(messages, form, request.getParameter(prefix + "field"),
                    request.getParameter(prefix + "value"), request.getParameter(prefix + "action"),
                    request.getParameter(prefix + "proposed"));
        } else {
            outcome = EditorFixApplier.apply(messages, form, null, null, null, null);
        }
        populate(model, user, selected, outcome.form(), Map.of());
        if (pilot.enabled()) {
            model.addAttribute("pilotDraft", request.getParameter("pilotDraft"));
        }
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
        var usage = plans.usage(user.clientId());
        EditorCost cost = EditorCost.of(usage);
        model.addAttribute("cost", cost);
        model.addAttribute("listingPackIncluded", usage.packs().contains(Pack.LISTING_RULES.id()));
        model.addAttribute("trademarkAvailable", cost.trademarkIncluded() && checks.trademarkAvailable(user.clientId()));
        model.addAttribute("fieldErrors", errors);
        model.addAttribute("otherOpen", form.hasOtherFields());
    }

    /** The refusals of the form as the page prints them: {@link EditorListingMapper} gives the key of each message. */
    private Map<String, String> texts(Map<String, String> keys) {
        Map<String, String> texts = new LinkedHashMap<>();
        keys.forEach((field, key) -> texts.put(field, messages.get(key)));
        return texts;
    }

    static EditorMarketplace marketplace(String code) {
        return EditorMarketplace.parse(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown marketplace."));
    }
}
