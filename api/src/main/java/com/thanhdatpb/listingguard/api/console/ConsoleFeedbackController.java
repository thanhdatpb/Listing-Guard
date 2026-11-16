package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.feedback.FindingFeedbackService;
import com.thanhdatpb.listingguard.api.feedback.FindingNotFoundException;
import com.thanhdatpb.listingguard.api.feedback.InvalidFeedbackException;
import com.thanhdatpb.listingguard.api.feedback.Reviewer;
import com.thanhdatpb.listingguard.api.feedback.StoredVerdict;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * {@code POST /console/feedback}: the signed-in account gives or changes its verdict on one stored finding (ADR-034). With htmx
 * (header {@code HX-Request}) the answer is the widget alone, which replaces the one that was pressed, so the page is not reloaded;
 * without a script it is a small page that says what was saved. A finding of another client, an unknown one and an expired one are
 * one {@code 404}, never a {@code 403} (ADR-007). It is behind the console's session and CSRF chain like every console page.
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleFeedbackController {

    static final String WIDGET = "console/feedback :: widget";
    static final String PAGE = "console/feedback-saved";

    private final FindingFeedbackService feedback;
    private final ConsoleFeedbackSupport support;
    private final ConsoleMessages messages;

    public ConsoleFeedbackController(FindingFeedbackService feedback, ConsoleFeedbackSupport support, ConsoleMessages messages) {
        this.feedback = feedback;
        this.support = support;
        this.messages = messages;
    }

    @PostMapping("/console/feedback")
    public String save(@RequestParam(name = "pack", defaultValue = "") String pack,
                       @RequestParam(name = "checkId", defaultValue = "") String checkId,
                       @RequestParam(name = "position", defaultValue = "") String position,
                       @RequestParam(name = "verdict", defaultValue = "") String verdict,
                       @RequestParam(name = "note", required = false) String note,
                       @RequestHeader(name = "HX-Request", required = false) String htmx,
                       @AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        boolean fragment = "true".equalsIgnoreCase(htmx);
        Optional<FeedbackTarget> target = target(pack, checkId, position);
        Optional<UUID> account = support.accountId(user);
        model.addAttribute("email", user.email());
        model.addAttribute("feedbackNoteMax", FindingFeedbackService.MAX_NOTE_CHARS);
        model.addAttribute(ConsoleFeedbackSupport.ENABLED, true);
        if (target.isEmpty() || account.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            model.addAttribute("feedbackError", messages.get("feedback.error.notfound"));
            return fragment && target.isPresent() ? failed(model, target.get(), note) : PAGE;
        }
        try {
            StoredVerdict stored = feedback.record(user.clientId(), Reviewer.account(account.get()), target.get().pack(),
                    target.get().checkId(), target.get().position(), verdict, note);
            model.addAttribute(ConsoleFeedbackSupport.VERDICTS,
                    Map.of(target.get().key(), new FindingFeedbackService.Mine(stored.verdict(), stored.note())));
            model.addAttribute("feedbackSavedKey", target.get().key());
            model.addAttribute("feedbackSavedVerdict", stored.verdict().css());
            model.addAttribute("target", target.get());
            return fragment ? WIDGET + "(target=${target})" : PAGE;
        } catch (InvalidFeedbackException e) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("feedbackError", switch (e.reason()) {
                case NOTE_TOO_LONG -> messages.get("feedback.error.notetoolong", e.noteLength(), FindingFeedbackService.MAX_NOTE_CHARS);
                case VERDICT -> messages.get("feedback.error.verdict");
                case PACK -> messages.get("feedback.error.notfound");
            });
            return fragment ? failed(model, target.get(), note) : PAGE;
        } catch (FindingNotFoundException e) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            model.addAttribute("feedbackError", messages.get("feedback.error.notfound"));
            return fragment ? failed(model, target.get(), note) : PAGE;
        }
    }

    /** The widget again with what was typed and why it was not saved; the verdict shown is the one stored before, if any. */
    private String failed(Model model, FeedbackTarget target, String note) {
        model.addAttribute("feedbackErrorKey", target.key());
        model.addAttribute("feedbackDraft", note == null || note.isBlank() ? null : note);
        model.addAttribute(ConsoleFeedbackSupport.VERDICTS, Map.of());
        model.addAttribute("target", target);
        return WIDGET + "(target=${target})";
    }

    /** The address the form names, or empty when it cannot be one (an unknown pack, a malformed id or index). */
    private static Optional<FeedbackTarget> target(String pack, String checkId, String position) {
        if (!FeedbackTarget.PACKS.contains(pack) || !position.matches("\\d{1,6}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new FeedbackTarget(pack, UUID.fromString(checkId), Integer.parseInt(position)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
