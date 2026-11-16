package com.thanhdatpb.listingguard.api.console.editor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.console.ConsoleFeedbackSupport;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.rewrite.ListingRewriter;
import com.thanhdatpb.listingguard.api.rewrite.RewriteOutcome;
import com.thanhdatpb.listingguard.api.rewrite.RewriteRunStore;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The "Gợi ý sửa" button of the editor and of each bulk row (ADR-037). It exists only when the console and
 * {@code listing-guard.llm.rewrite.enabled} are both on. A press posts the listing the page already holds, asks
 * {@link ListingRewriter} and prints the suggestions that passed every check; it changes no field of the editor, stores no text of the
 * listing and never writes to a marketplace. With htmx the answer is the panel alone, without a script it is a small page.
 *
 * <p>{@code POST /console/rewrite/used} records that the seller copied a suggestion of a run of their client, for the use-rate.
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard", name = {"console.enabled", "llm.rewrite.enabled"}, havingValue = "true")
public class ConsoleRewriteController {

    static final String PAGE = "console/rewrite-page";
    static final String PANEL = "console/rewrite :: panel";

    private final ListingRewriter rewriter;
    private final RewriteRunStore runs;
    private final ConsoleFeedbackSupport feedback;
    private final TrademarkRulesets trademarkRulesets;
    private final ObjectMapper mapper;

    public ConsoleRewriteController(ListingRewriter rewriter, RewriteRunStore runs, ConsoleFeedbackSupport feedback,
                                    TrademarkRulesets trademarkRulesets, ObjectMapper mapper) {
        this.rewriter = rewriter;
        this.runs = runs;
        this.feedback = feedback;
        this.trademarkRulesets = trademarkRulesets;
        this.mapper = mapper;
    }

    /** From the editor: the form as the page holds it (the fields of {@link EditorForm}), mapped like a check. */
    @PostMapping("/console/rewrite/editor")
    public String editor(@ModelAttribute("form") EditorForm form,
                         @RequestParam(name = "pack", defaultValue = "") String pack,
                         @RequestParam(name = "checkId", defaultValue = "") String checkId,
                         @RequestParam(name = "position", defaultValue = "") String position,
                         @RequestHeader(name = "HX-Request", required = false) String htmx,
                         @AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        EditorMarketplace marketplace = ConsoleEditorController.marketplace(form.marketplace());
        EditorListingMapper.Mapped mapped = EditorListingMapper.map(marketplace, form);
        if (!mapped.valid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The listing could not be read.");
        }
        return answer(user, marketplace, mapped.listing(), target(pack, checkId, position), htmx, model, response);
    }

    /** From a bulk row: the row's listing as standard listing JSON, carried in the result page. */
    @PostMapping("/console/rewrite/bulk")
    public String bulk(@RequestParam(name = "marketplace", defaultValue = "") String marketplaceCode,
                       @RequestParam(name = "listing", defaultValue = "") String listing,
                       @RequestParam(name = "pack", defaultValue = "") String pack,
                       @RequestParam(name = "checkId", defaultValue = "") String checkId,
                       @RequestParam(name = "position", defaultValue = "") String position,
                       @RequestHeader(name = "HX-Request", required = false) String htmx,
                       @AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        EditorMarketplace marketplace = ConsoleEditorController.marketplace(marketplaceCode);
        JsonNode parsed;
        try {
            parsed = mapper.readTree(listing);
        } catch (JsonProcessingException e) {
            // never echo the listing or the parser's message: either can hold the seller's text
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The listing could not be read.");
        }
        if (parsed == null || !parsed.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The listing could not be read.");
        }
        return answer(user, marketplace, (ObjectNode) parsed, target(pack, checkId, position), htmx, model, response);
    }

    /**
     * The seller copied suggestion {@code candidate} of run {@code runId}. A run of another client, an unknown one and a number the
     * run did not offer are one {@code 404} (ADR-007).
     */
    @PostMapping("/console/rewrite/used")
    public ResponseEntity<Void> used(@RequestParam(name = "runId", defaultValue = "") String runId,
                                     @RequestParam(name = "candidate", defaultValue = "") String candidate,
                                     @AuthenticationPrincipal ConsoleUser user) {
        UUID run;
        int number;
        try {
            run = UUID.fromString(runId);
            number = Integer.parseInt(candidate);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }
        boolean recorded = runs.markUsed(user.clientId(), run, number);
        return ResponseEntity.status(recorded ? HttpStatus.NO_CONTENT : HttpStatus.NOT_FOUND)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private String answer(ConsoleUser user, EditorMarketplace marketplace, ObjectNode listing, Optional<FeedbackTarget> anchor,
                          String htmx, Model model, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        RewriteOutcome outcome = rewriter.suggest(user.clientId(), feedback.accountId(user).orElse(null), marketplace.code(), listing,
                anchor.orElse(null));
        model.addAttribute("rewrite", outcome);
        model.addAttribute("rewriteDisclaimer", trademarkRulesets.ruleset().disclaimer());
        model.addAttribute("email", user.email());
        model.addAttribute("marketplace", marketplace);
        return "true".equalsIgnoreCase(htmx) ? PANEL : PAGE;
    }

    /** The finding the press came from, or empty when the form names none or something that cannot be an address. */
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
