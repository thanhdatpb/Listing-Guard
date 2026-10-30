package com.thanhdatpb.listingguard.api.trademark;

import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_INVALID_REQUEST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RATE_LIMITED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_TOO_LARGE;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_TRADEMARK_NOT_CONFIGURED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_TRADEMARK_NOT_FOUND;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNAUTHORIZED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.TRADEMARK_REQUEST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.TRADEMARK_RESULT;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.TRADEMARK_RESULT_CACHED;

import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.security.ApiKeyFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The trademark pack's own endpoint (ADR-009, ADR-010). {@code /api/v1/checks} neither shows nor depends on it. */
@RestController
@RequestMapping("/api/v1/trademark-checks")
@Tag(name = "Trademark checks", description = "Possible trademark references in a listing's text, as a reference warning.")
public class TrademarkCheckController {

    private static final String PROBLEM = "application/problem+json";

    private final TrademarkCheckService service;

    public TrademarkCheckController(TrademarkCheckService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            summary = "Check a listing for possible trademark references",
            description = """
                    Takes the same request as `POST /api/v1/checks` (`marketplace` and `listing` in the marketplace's own \
                    format), so a client can send one JSON to both endpoints. Compares the listing's text with the \
                    configured trademark catalog and returns findings with evidence: the field, the span in the text the API \
                    worked on (after trimming and Unicode NFC, UTF-16 offsets), the matched mark and how it matched. The \
                    result is a reference warning, not a legal conclusion; show the `disclaimer` with it. If the same client \
                    already sent the same listing text with the same analysisVersion and the stored result is complete and \
                    still retained, it is returned with `cached: true`. An INCOMPLETE result (a rule failed) is stored so \
                    its id can be read again, and is never reused for another request. Results are kept for a limited time \
                    (`expiresAt`); only the listing's hash and the result are stored, not the listing text.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CheckRequest.class),
                            examples = @ExampleObject(name = "etsy-listing", summary = "An Etsy listing (synthetic names)",
                                    value = TRADEMARK_REQUEST))),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The trademark check result.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TrademarkCheckResult.class),
                                    examples = {
                                            @ExampleObject(name = "findings", summary = "A fresh check with findings", value = TRADEMARK_RESULT),
                                            @ExampleObject(name = "cached", summary = "The same listing again: the stored result",
                                                    value = TRADEMARK_RESULT_CACHED)})),
                    @ApiResponse(responseCode = "400", description = "Invalid request (code `invalid-request`) or unknown marketplace (`unknown-marketplace`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(name = "invalid-request", value = PROBLEM_INVALID_REQUEST))),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "413", description = "The request body is over the size limit (256 KB).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_TOO_LARGE))),
                    @ApiResponse(responseCode = "415", description = "The body is not application/json (code `unsupported-media-type`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "The marketplace is planned but not supported yet (code `marketplace-not-yet-supported`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client (the same limit as the other endpoints); see the Retry-After header.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED))),
                    @ApiResponse(responseCode = "503", description = "Trademark checks are not available on this server: no catalog is configured or the configured one is refused (code `trademark-not-configured`). `POST /api/v1/checks` is not affected. Retrying does not help; an operator must configure a catalog.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_TRADEMARK_NOT_CONFIGURED)))})
    public TrademarkCheckResult check(@Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                                      @Valid @RequestBody CheckRequest request) {
        return service.check(client.id(), request);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Read a stored trademark check",
            description = "Returns the stored result of an earlier trademark check of the calling client while it is retained, in the "
                    + "same shape as the POST response (`cached` is false). After `expiresAt`, for an unknown id and for another "
                    + "client's check the answer is the same 404.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The stored result.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TrademarkCheckResult.class),
                                    examples = @ExampleObject(value = TRADEMARK_RESULT))),
                    @ApiResponse(responseCode = "400", description = "The id is not a UUID.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "404", description = "No retained trademark check with this id for the calling client (code `trademark-check-not-found`). A check of another client, an id that never existed and an expired check give the same answer.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_TRADEMARK_NOT_FOUND))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public TrademarkCheckResult get(@Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                                    @Parameter(description = "checkId returned by POST /api/v1/trademark-checks") @PathVariable UUID id) {
        return service.get(client.id(), id);
    }
}
