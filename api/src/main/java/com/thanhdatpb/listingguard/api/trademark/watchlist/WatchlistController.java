package com.thanhdatpb.listingguard.api.trademark.watchlist;

import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_PLAN_NOT_ALLOWED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RATE_LIMITED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_TOO_LARGE;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNAUTHORIZED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_WATCHLIST_INVALID;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_WATCHLIST_REVISION_MISMATCH;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.TRADEMARK_WATCHLIST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.TRADEMARK_WATCHLIST_REQUEST;

import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.security.ApiKeyFilter;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.ReplaceRequest;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.WatchlistView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling client's trademark watchlist (ADR-031). A list is the client's own data: the client is the key's, there is no
 * client parameter. Reading and replacing it costs no quota.
 */
@RestController
@RequestMapping("/api/v1/trademark-watchlist")
@Tag(name = "Trademark watchlist", description = "The client's own list of marks, reported by every trademark check.")
public class WatchlistController {

    private static final String PROBLEM = "application/problem+json";
    /** A strong ETag of a revision: {@code "3"}. A weak one ({@code W/"3"}) names the same revision. */
    private static final Pattern REVISION_TAG = Pattern.compile("(?:W/)?\"([0-9]{1,18})\"");

    private final WatchlistService service;

    public WatchlistController(WatchlistService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(
            operationId = "getTrademarkWatchlist",
            summary = "Read the client's trademark watchlist",
            description = """
                    Returns the calling client's watchlist: every entry (enabled or not) in the client's order, the `revision` \
                    (0 when the client never had a list), the `version` a trademark check reports while at least one entry is \
                    enabled, and the deployment's limits. Every trademark check of the client looks for the enabled entries' \
                    marks and aliases next to the catalog; a match is a finding with `source` `WATCHLIST`. The answer carries \
                    an `ETag` of the revision. It needs the `trademark-risk` plan pack where plans are on and costs no quota.""",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The client's watchlist.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WatchlistView.class),
                                    examples = @ExampleObject(value = TRADEMARK_WATCHLIST))),
                    @ApiResponse(responseCode = "304", description = "The `If-None-Match` header matches the `ETag`: nothing changed, there is no body."),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "403", description = "The client's plan does not include `trademark-risk` (code `plan-not-allowed`; only where plans are on).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_PLAN_NOT_ALLOWED))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public ResponseEntity<WatchlistView> get(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client) {
        return answer(service.read(client.id()));
    }

    @PutMapping
    @Operation(
            operationId = "replaceTrademarkWatchlist",
            summary = "Replace the client's trademark watchlist",
            description = """
                    Replaces the calling client's whole watchlist with `entries`, in that order. An entry is identified by the \
                    normalized form of its mark (case, accents and punctuation are ignored): an entry whose mark is already \
                    listed keeps its `id`, `addedBy` and `addedAt`. No two marks or aliases of the list may have the same \
                    normalized form. Every problem of the list is answered at once (`invalid-request` with `errors`) and nothing \
                    is stored. With `If-Match: "<revision>"` the list is replaced only if it is still at that revision (else \
                    `412 watchlist-revision-mismatch`); without it, whatever is stored is replaced. An identical list changes \
                    nothing, not even the revision. A change of an enabled entry changes the `analysisVersion` of the client's \
                    next trademark checks, so they are analysed again. It needs the `trademark-risk` plan pack where plans are \
                    on and costs no quota. Nothing is written to a marketplace.""",
            parameters = @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = false,
                    description = "The revision the change is based on, as the `ETag` of GET gave it, for example `\"3\"`.",
                    schema = @Schema(type = "string")),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReplaceRequest.class),
                            examples = @ExampleObject(value = TRADEMARK_WATCHLIST_REQUEST))),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The watchlist as stored now.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WatchlistView.class),
                                    examples = @ExampleObject(value = TRADEMARK_WATCHLIST))),
                    @ApiResponse(responseCode = "400", description = "The list or the `If-Match` header is not valid (code `invalid-request`); `errors` names every problem with its `field`, such as `entries[1].aliases[0]`.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_WATCHLIST_INVALID))),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "403", description = "The client's plan does not include `trademark-risk` (code `plan-not-allowed`; only where plans are on).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_PLAN_NOT_ALLOWED))),
                    @ApiResponse(responseCode = "412", description = "The list is no longer at the revision of `If-Match` (code `watchlist-revision-mismatch`; the current one is in `currentRevision`). Read it again and apply the change to the current list.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_WATCHLIST_REVISION_MISMATCH))),
                    @ApiResponse(responseCode = "413", description = "The request body is over the size limit (256 KB).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_TOO_LARGE))),
                    @ApiResponse(responseCode = "415", description = "The body is not application/json (code `unsupported-media-type`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public ResponseEntity<WatchlistView> replace(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @Parameter(hidden = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ReplaceRequest request) {
        Long expected = expectedRevision(ifMatch);
        return answer(service.replace(client.id(), request.entries(), "api-key:" + client.keyId(), expected));
    }

    private static ResponseEntity<WatchlistView> answer(WatchlistView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePrivate())
                .eTag("\"" + view.revision() + "\"").body(view);
    }

    /** The revision of an {@code If-Match} header, null without one; anything else is the client's mistake. */
    static Long expectedRevision(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        Matcher matcher = REVISION_TAG.matcher(ifMatch.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("If-Match must be the ETag of a revision, for example \"3\".");
        }
        return Long.parseLong(matcher.group(1));
    }
}
