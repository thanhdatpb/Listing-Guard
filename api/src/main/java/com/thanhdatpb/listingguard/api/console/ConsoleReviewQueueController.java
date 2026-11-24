package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.review.ReviewCase;
import com.thanhdatpb.listingguard.api.review.ReviewCaseException;
import com.thanhdatpb.listingguard.api.review.ReviewCaseService;
import com.thanhdatpb.listingguard.api.review.ReviewCaseStatus;
import com.thanhdatpb.listingguard.api.review.ReviewDecision;
import com.thanhdatpb.listingguard.api.review.VerifiedCases;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Hidden
@Controller
@RequestMapping("/console/review-queue")
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.review-cases.enabled:false}")
public class ConsoleReviewQueueController {
    private static final int PAGE_SIZE = 25;

    public enum Age {
        ALL(0), WEEK(7), MONTH(30);

        private final int days;

        Age(int days) {
            this.days = days;
        }
    }

    private final ReviewCaseService cases;
    private final Clock clock;
    private final VerifiedCases verifiedCases;
    private final ConsoleMessages messages;

    public ConsoleReviewQueueController(ReviewCaseService cases, Clock clock, ConsoleMessages messages, VerifiedCases verifiedCases) {
        this.cases = cases;
        this.clock = clock;
        this.messages = messages;
        this.verifiedCases = verifiedCases;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @ModelAttribute("reviewStatusLabels")
    Map<ReviewCaseStatus, String> statusLabels() {
        return Map.of(ReviewCaseStatus.OPEN, messages.get("review.open"),
                ReviewCaseStatus.IN_REVIEW, messages.get("review.in.review"),
                ReviewCaseStatus.RESOLVED, messages.get("review.resolved"),
                ReviewCaseStatus.DISMISSED, messages.get("review.dismissed"));
    }

    @GetMapping
    public String queue(@AuthenticationPrincipal ConsoleUser user, Model model,
                        @RequestParam(required = false) ReviewCaseStatus status,
                        @RequestParam(required = false) ReviewCase.Marketplace marketplace,
                        @RequestParam(required = false) ReviewCase.Severity severity,
                        @RequestParam(defaultValue = "ALL") Age age,
                        @RequestParam(required = false) OffsetDateTime cutoff,
                        @RequestParam(required = false) String cursor) {
        if (cursor == null || age == Age.ALL) {
            cutoff = age == Age.ALL ? null : clock.instant().atOffset(ZoneOffset.UTC).minusDays(age.days);
        } else if (cutoff == null) {
            throw new IllegalArgumentException("An age filter requires its original cutoff when paging.");
        }
        model.addAttribute("page", cases.list(user.clientId(), status, marketplace, severity, null, cutoff,
                ReviewCaseService.Sort.NEWEST, PAGE_SIZE, cursor));
        model.addAttribute("counts", cases.counts(user.clientId()));
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedMarketplace", marketplace);
        model.addAttribute("selectedSeverity", severity);
        model.addAttribute("selectedAge", age.name());
        model.addAttribute("statuses", ReviewCaseStatus.values());
        model.addAttribute("severities", ReviewCase.Severity.values());
        model.addAttribute("cutoff", cutoff == null ? null : cutoff.toInstant().toString());
        return "console/review-queue";
    }

    @GetMapping("/{id}")
    public String detail(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, Model model) {
        ReviewCaseService.Detail detail = cases.get(user.clientId(), id);
        model.addAttribute("detail", detail);
        List<ReviewCaseStatus> transitions = Arrays.stream(ReviewCaseStatus.values())
                .filter(detail.reviewCase().status()::permits).toList();
        if (detail.evidenceAvailable() && !transitions.isEmpty()) {
            model.addAttribute("canReview", cases.canReviewInConsole(user));
            model.addAttribute("transitions", transitions);
            model.addAttribute("requestId", UUID.randomUUID());
        }
        if (verifiedCases.enabled() && cases.canReviewInConsole(user)) {
            try {
                model.addAttribute("verifiedView", verifiedCases.view(user, id));
            } catch (DataAccessException e) {
                model.addAttribute("verifiedUnavailable", true);
            }
        }
        return "console/review-case";
    }

    @PostMapping("/{id}/verified")
    public String verify(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id,
                         @RequestParam(defaultValue = "false") boolean confirmed) {
        if (!confirmed) {
            throw new IllegalArgumentException("A verification must be confirmed.");
        }
        verifiedCases.verify(user, id);
        return "redirect:/console/review-queue/{id}";
    }

    @PostMapping("/{id}/verified/revoke")
    public String revoke(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id,
                         @RequestParam(defaultValue = "false") boolean confirmed) {
        if (!confirmed) {
            throw new IllegalArgumentException("A revocation must be confirmed.");
        }
        verifiedCases.revoke(user, id);
        return "redirect:/console/review-queue/{id}";
    }

    @GetMapping("/{id}/evidence")
    public String evidence(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, Model model) {
        model.addAttribute("evidence", cases.retainedEvidence(user.clientId(), id));
        model.addAttribute("caseId", id);
        return "console/review-case";
    }

    @PostMapping("/{id}/decisions")
    public String decide(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id,
                         @RequestParam UUID requestId, @RequestParam int revision, @RequestParam ReviewCaseStatus status,
                         @RequestParam(defaultValue = "false") boolean confirmed, RedirectAttributes redirect) {
        if (!confirmed || revision < 0) {
            throw new IllegalArgumentException("A confirmed decision and a valid revision are required.");
        }
        cases.decideInConsole(user, id, new ReviewDecision(requestId, revision, status, status.requiredReason()));
        redirect.addFlashAttribute("decisionSaved", true);
        return "redirect:/console/review-queue/{id}";
    }

    @ExceptionHandler(ReviewCaseException.class)
    String reviewError(ReviewCaseException error, Model model, HttpServletResponse response) {
        return errorPage(error.status(), model, response);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class,
            IllegalArgumentException.class})
    String invalidRequest(Model model, HttpServletResponse response) {
        return errorPage(HttpStatus.BAD_REQUEST, model, response);
    }

    @ExceptionHandler(DataAccessException.class)
    String unavailable(Model model, HttpServletResponse response) {
        return errorPage(HttpStatus.SERVICE_UNAVAILABLE, model, response);
    }

    private String errorPage(HttpStatus status, Model model, HttpServletResponse response) {
        noStore(response);
        response.setStatus(status.value());
        model.addAttribute("reviewError", status.value());
        return "console/review-queue";
    }
}
