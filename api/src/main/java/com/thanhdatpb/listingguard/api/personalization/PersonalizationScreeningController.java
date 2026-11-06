package com.thanhdatpb.listingguard.api.personalization;

import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PERSONALIZATION_REQUEST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PERSONALIZATION_RESULT;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PERSONALIZATION_RESULT_CACHED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_INVALID_REQUEST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_PERSONALIZATION_NOT_FOUND;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_PLAN_NOT_ALLOWED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_QUOTA_EXCEEDED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RATE_LIMITED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_TOO_LARGE;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNAUTHORIZED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNKNOWN_PRINT_PROFILE;

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

/**
 * The personalization-screening pack's own endpoint (ADR-016, ADR-010). {@code /api/v1/checks},
 * {@code /api/v1/trademark-checks} and {@code /api/v1/variation-checks} neither show nor depend on it.
 */
@RestController
@RequestMapping("/api/v1/personalization-screenings")
@Tag(name = "Personalization screening",
        description = "The text a buyer typed for a custom product: can it be printed, and does it look like a protected name.")
public class PersonalizationScreeningController {

    private static final String PROBLEM = "application/problem+json";

    private final PersonalizationScreeningService service;

    public PersonalizationScreeningController(PersonalizationScreeningService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            operationId = "screenPersonalization",
            summary = "Screen the personalization text of an order",
            description = """
                    Takes the text a buyer typed for a custom product (a name, a number), one entry per field of the order, and a \
                    print profile (see docs/input-schema.md, "Personalization screening"). It is meant for the step before an order \
                    goes into production, not for listings. Two independent answers: the character rules (characters the print \
                    profile cannot print, a text that is too long, stray spaces) in `findings`, and the trademark scan of the same \
                    text in `trademark`. Show both; neither replaces the other. If no trademark catalog is served, the character rules \
                    still run and `trademark.status` is NOT_CONFIGURED with `riskLevel` UNKNOWN (UNKNOWN is never LOW), and the result \
                    is INCOMPLETE. The result is a reference warning built on an internal print policy and a catalog, not a statement \
                    of the print workshop, a marketplace or a court; show the `disclaimer` with it. It proposes no replacement and \
                    changes no text. The answer never repeats the text; use the offsets (UTF-16 code units) to find a finding in it. \
                    If the same client already sent the same values with the same analysisVersion and the stored result is complete \
                    and still retained, it is returned with `cached: true`. An INCOMPLETE result is stored so its id can be read \
                    again, and is never reused for another request. A new complete result counts one unit of the client's monthly \
                    quota where plans are on (see `GET /api/v1/usage`); a cached or an INCOMPLETE one costs nothing. Results are kept \
                    for a limited time (`expiresAt`); the text the buyer typed is never stored, only its hash and the result.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PersonalizationScreeningRequest.class),
                            examples = @ExampleObject(name = "jersey-order", summary = "A name and a number (synthetic)",
                                    value = PERSONALIZATION_REQUEST))),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The screening result.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PersonalizationScreeningResult.class),
                                    examples = {
                                            @ExampleObject(name = "findings", summary = "A fresh screening with findings", value = PERSONALIZATION_RESULT),
                                            @ExampleObject(name = "cached", summary = "The same values again: the stored result",
                                                    value = PERSONALIZATION_RESULT_CACHED)})),
                    @ApiResponse(responseCode = "400", description = "Invalid request (code `invalid-request`), unknown marketplace (`unknown-marketplace`) or a print profile the ruleset does not declare (`unknown-print-profile`, with the declared codes in `known`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = {
                                            @ExampleObject(name = "invalid-request", value = PROBLEM_INVALID_REQUEST),
                                            @ExampleObject(name = "unknown-print-profile", value = PROBLEM_UNKNOWN_PRINT_PROFILE)})),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "403", description = "The client's plan does not include this pack (code `plan-not-allowed`; only where plans are on). Retrying does not help.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_PLAN_NOT_ALLOWED))),
                    @ApiResponse(responseCode = "413", description = "The request body is over the size limit (256 KB).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_TOO_LARGE))),
                    @ApiResponse(responseCode = "415", description = "The body is not application/json (code `unsupported-media-type`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "The marketplace is planned but not supported yet (code `marketplace-not-yet-supported`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "429", description = "Two different answers, told apart by `code`. `rate-limited`: too many requests for the calling client (the same limit as the other endpoints); retry after the Retry-After header. `quota-exceeded` (only where plans are on): the month's quota of the client's plan is used up; there is no Retry-After, and retrying before `resetAt` does not help. A result already stored is still answered.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = {
                                            @ExampleObject(name = "rate-limited", value = PROBLEM_RATE_LIMITED),
                                            @ExampleObject(name = "quota-exceeded", value = PROBLEM_QUOTA_EXCEEDED)}))})
    public PersonalizationScreeningResult check(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @Valid @RequestBody PersonalizationScreeningRequest request) {
        return service.check(client.id(), request);
    }

    @GetMapping("/{id}")
    @Operation(
            operationId = "getPersonalizationScreening",
            summary = "Read a stored personalization screening",
            description = "Returns the stored result of an earlier screening of the calling client while it is retained, in the "
                    + "same shape as the POST response (`cached` is false). After `expiresAt`, for an unknown id and for another "
                    + "client's screening the answer is the same 404.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The stored result.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PersonalizationScreeningResult.class),
                                    examples = @ExampleObject(value = PERSONALIZATION_RESULT))),
                    @ApiResponse(responseCode = "400", description = "The id is not a UUID.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "404", description = "No retained personalization screening with this id for the calling client (code `personalization-screening-not-found`). A screening of another client, an id that never existed and an expired screening give the same answer.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_PERSONALIZATION_NOT_FOUND))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public PersonalizationScreeningResult get(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @Parameter(description = "checkId returned by POST /api/v1/personalization-screenings") @PathVariable UUID id) {
        return service.get(client.id(), id);
    }
}
