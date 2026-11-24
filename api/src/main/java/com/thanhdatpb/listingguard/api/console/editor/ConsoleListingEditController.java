package com.thanhdatpb.listingguard.api.console.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.thanhdatpb.listingguard.api.console.ConsoleFeedbackSupport;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.edit.ListingEditRequest;
import com.thanhdatpb.listingguard.api.edit.ListingEditResult;
import com.thanhdatpb.listingguard.api.edit.ListingEditService;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.policy.PolicyChecks;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

/** Side-by-side revision comparison for the console editor (ADR-033). */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleListingEditController {
    private static final String VIEW = "console/edit-mode";
    private final ListingEditService edits;
    private final ConsoleFeedbackSupport feedback;
    private final com.thanhdatpb.listingguard.api.history.CheckHistoryRecorder history;
    private final ObjectMapper mapper;
    private final PolicyChecks policyChecks;

    public ConsoleListingEditController(ListingEditService edits, ConsoleFeedbackSupport feedback,
                                        com.thanhdatpb.listingguard.api.history.CheckHistoryRecorder history,
                                        ObjectMapper mapper, PolicyChecks policyChecks) {
        this.edits = edits;
        this.feedback = feedback;
        this.history = history;
        this.mapper = mapper;
        this.policyChecks = policyChecks;
    }

    @GetMapping("/console/check/edit")
    public String page(@RequestParam(defaultValue = "ETSY") String marketplace,
                       @AuthenticationPrincipal ConsoleUser user, Model model) {
        return populate(user, marketplace, "", "", model);
    }

    @PostMapping("/console/check/edit")
    public String compare(@RequestParam String marketplace, @RequestParam String before, @RequestParam String after,
                          @AuthenticationPrincipal ConsoleUser user, Model model) {
        if (before.isBlank() || after.isBlank()) {
            model.addAttribute("error", "edit.error.required");
            return populate(user, marketplace, before, after, model);
        }
        try {
            JsonNode beforeListing = mapper.readTree(before);
            JsonNode afterListing = mapper.readTree(after);
            if (beforeListing == null || afterListing == null || !beforeListing.isObject() || !afterListing.isObject()) {
                model.addAttribute("error", "edit.error.object");
                return populate(user, marketplace, before, after, model);
            }
            var comparison = edits.checkConsole(user.clientId(), new ListingEditRequest(marketplace, beforeListing, afterListing,
                    null, null));
            ListingEditResult result = comparison.result();
            model.addAttribute("comparison", comparison);
            feedback.accountId(user).ifPresent(account -> {
                record(user.clientId(), account, result.before());
                record(user.clientId(), account, result.after());
            });
            model.addAttribute("result", result);
            List<FeedbackTarget> targets = new ArrayList<>();
            result.newFindings().forEach(f -> targets.add(f.feedbackTarget()));
            result.modifiedFindings().forEach(f -> targets.add(f.feedbackTarget()));
            result.remainingFindings().forEach(f -> targets.add(f.feedbackTarget()));
            feedback.populate(model, user, targets);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Do not echo or log either version: a parser message can contain the listing text.
            model.addAttribute("error", "edit.error.invalid");
        }
        return populate(user, marketplace, before, after, model);
    }

    private String populate(ConsoleUser user, String marketplace, String before, String after, Model model) {
        model.addAttribute("email", user.email());
        model.addAttribute("marketplace", marketplace);
        model.addAttribute("policyAiEnabled", "ETSY".equalsIgnoreCase(marketplace) && policyChecks.enabled());
        model.addAttribute("before", before);
        model.addAttribute("after", after);
        return VIEW;
    }

    private void record(java.util.UUID clientId, java.util.UUID accountId, ListingEditResult.Side side) {
        if (side.listingRules() != null) {
            history.account(clientId, accountId, com.thanhdatpb.listingguard.api.plan.Pack.LISTING_RULES.id(),
                    side.listingRules().checkId(), side.listingRules().marketplace(), 1, null);
        }
        if (side.trademarkRisk() != null) {
            history.account(clientId, accountId, com.thanhdatpb.listingguard.api.plan.Pack.TRADEMARK_RISK.id(),
                    side.trademarkRisk().checkId(), side.trademarkRisk().marketplace(), 1, null);
        }
    }
}
