package com.thanhdatpb.listingguard.api.console.editor;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.review.ReviewCaseException;
import com.thanhdatpb.listingguard.api.review.ReviewCaseService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Hidden
@Controller
@RequestMapping("/console/pod-readiness")
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.console.pod-enabled:true}")
class ConsolePodController {
    private static final String VIEW = "console/pod-readiness";
    private final PodProfiles profiles;
    private final PodReadiness readiness;
    private final ReviewCaseService reviews;
    private final ConsoleMessages messages;
    private final boolean reviewEnabled;

    ConsolePodController(PodProfiles profiles, PodReadiness readiness, ReviewCaseService reviews, ConsoleMessages messages,
                         @Value("${listing-guard.review-cases.enabled:false}") boolean reviewEnabled) {
        this.profiles = profiles;
        this.readiness = readiness;
        this.reviews = reviews;
        this.messages = messages;
        this.reviewEnabled = reviewEnabled;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping
    String form(@AuthenticationPrincipal ConsoleUser user, Model model) {
        return populate(model, user, EditorForm.empty(EditorMarketplace.ETSY),
                new PodDraft(null, null, null, null, null, null, false, false));
    }

    @PostMapping
    String check(@AuthenticationPrincipal ConsoleUser user, @ModelAttribute EditorForm listing,
                 @ModelAttribute PodDraft draft, Model model, HttpServletResponse response) {
        String view = populate(model, user, listing, draft);
        EditorMarketplace marketplace = EditorMarketplace.parse(listing.marketplace()).orElse(null);
        PodProfiles.Release release = profiles.find(draft.profileKey());
        if (marketplace == null || release == null || !release.profile().marketplaces().contains(marketplace)) {
            model.addAttribute("podError", "pod.error.profile");
            return view;
        }
        if (!bounded(listing)) {
            model.addAttribute("podError", "pod.error.listing");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return view;
        }
        var mapped = EditorListingMapper.map(marketplace, listing);
        if (!mapped.valid()) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            mapped.errors().forEach((field, key) -> fieldErrors.put(field, messages.get(key)));
            model.addAttribute("fieldErrors", fieldErrors);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return view;
        }
        try {
            var rows = draft.rows();
            var width = draft.width();
            var height = draft.height();
            model.addAttribute("result", readiness.check(user.clientId(), marketplace, release.profile(),
                    listing, mapped.listing(), draft, rows, width, height));
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            model.addAttribute("podError", "pod.error.input");
        } catch (DataAccessException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            model.addAttribute("podError", "pod.error.unavailable");
        }
        return view;
    }

    @PostMapping("/apply")
    String apply(@AuthenticationPrincipal ConsoleUser user, @ModelAttribute EditorForm listing,
                 @ModelAttribute PodDraft draft, @RequestParam String apply, HttpServletRequest request, Model model) {
        String prefix = "fix." + (apply.matches("[0-9]{1,4}") ? apply : "") + ".";
        String field = request.getParameter(prefix + "field");
        String proposed = request.getParameter(prefix + "proposed");
        EditorFixApplier.Outcome outcome = EditorFixApplier.apply(messages, listing,
                field != null && field.matches("title|tags\\[[0-9]{1,4}\\]") ? field : null,
                request.getParameter(prefix + "value"), request.getParameter(prefix + "action"),
                proposed != null && proposed.length() <= 1000 ? proposed : null);
        model.addAttribute("notice", outcome.message());
        return populate(model, user, outcome.form(), draft);
    }

    @PostMapping("/review")
    String review(@AuthenticationPrincipal ConsoleUser user, @RequestParam String pack, @RequestParam UUID checkId,
                  @RequestParam int position, @RequestParam(defaultValue = "false") boolean confirmed,
                  RedirectAttributes redirect) {
        if (!reviewEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!confirmed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        FeedbackTarget target;
        try {
            target = new FeedbackTarget(pack, checkId, position);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var review = reviews.createInConsole(user, target);
        redirect.addAttribute("id", review.id());
        return "redirect:/console/review-queue/{id}";
    }

    @ExceptionHandler(ReviewCaseException.class)
    String reviewError(ReviewCaseException exception, Model model, HttpServletResponse response) {
        response.setStatus(exception.status().value());
        model.addAttribute("podError", exception.status() == HttpStatus.NOT_FOUND ? "pod.error.evidence" : "pod.error.review");
        return form(null, model);
    }

    private String populate(Model model, ConsoleUser user, EditorForm listing, PodDraft draft) {
        model.addAttribute("listingForm", listing);
        model.addAttribute("draft", draft);
        model.addAttribute("profiles", profiles.released());
        model.addAttribute("release", profiles.find(draft.profileKey()));
        model.addAttribute("marketplaces", EditorMarketplace.values());
        if (reviewEnabled && user != null) {
            try {
                model.addAttribute("canReviewPod", reviews.canReviewInConsole(user));
                model.addAttribute("reviewMetrics", reviews.completion(user.clientId()));
            } catch (DataAccessException e) {
                model.addAttribute("reviewMetricsUnavailable", true);
            }
        }
        return VIEW;
    }

    private static boolean bounded(EditorForm listing) {
        return listing.title().length() <= 1000 && listing.description().length() <= 15000
                && listing.tags().length() <= 2000 && listing.bulletPoints().length() <= 5000
                && listing.searchTerms().length() <= 1000
                && List.of(listing.category(), listing.brand(), listing.whoMade(), listing.whenMade(), listing.productType(),
                           listing.price(), listing.quantity()).stream().allMatch(field -> field.length() <= 200);
    }
}
