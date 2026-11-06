package com.thanhdatpb.listingguard.api.variation;

import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_INVALID_REQUEST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_PLAN_NOT_ALLOWED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_QUOTA_EXCEEDED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RATE_LIMITED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_TOO_LARGE;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNAUTHORIZED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_VARIATION_NOT_FOUND;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.VARIATION_REQUEST_AMAZON;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.VARIATION_REQUEST_ETSY;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.VARIATION_RESULT;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.VARIATION_RESULT_CACHED;

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
 * The variation-checks pack's own endpoint (ADR-015, ADR-010). {@code /api/v1/checks} and
 * {@code /api/v1/trademark-checks} neither show nor depend on it.
 */
@RestController
@RequestMapping("/api/v1/variation-checks")
@Tag(name = "Variation checks", description = "Structure of a listing's parent/child variations, as a reference warning.")
public class VariationCheckController {

    private static final String PROBLEM = "application/problem+json";

    private final VariationCheckService service;

    public VariationCheckController(VariationCheckService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            summary = "Check the variation structure of a listing",
            description = """
                    Takes a variation family: for Amazon a `parent` and its `children`, for Etsy the `variation_properties` and the \
                    `children` (see docs/input-schema.md, "Variation checks"). It is not the `/checks` envelope: a family is not a \
                    listing. Checks that the parent and its children are consistent (parent SKU, theme and its attributes, apparel \
                    size attributes, repeated combinations and SKUs, children that look like different products) and, for Etsy, the \
                    limits on variation types. The result is a reference warning built on secondary sources and internal \
                    heuristics, not a statement of the marketplace; show the `disclaimer` with it. It proposes no fix and no \
                    replacement value. If the same client already sent the same family with the same analysisVersion and the stored \
                    result is complete and still retained, it is returned with `cached: true`. An INCOMPLETE result (a rule failed) \
                    is stored so its id can be read again, and is never reused for another request. A new complete result counts one \
                    unit of the client's monthly quota where plans are on (see `GET /api/v1/usage`); a cached or an INCOMPLETE one \
                    costs nothing. Results are kept for a limited time (`expiresAt`); only the family's hash and the result are \
                    stored, not the family itself.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = VariationCheckRequest.class),
                            examples = {
                                    @ExampleObject(name = "amazon-family", summary = "An Amazon parent with two children (synthetic)",
                                            value = VARIATION_REQUEST_AMAZON),
                                    @ExampleObject(name = "etsy-family", summary = "An Etsy listing with three variation types (synthetic)",
                                            value = VARIATION_REQUEST_ETSY)})),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The variation check result.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VariationCheckResult.class),
                                    examples = {
                                            @ExampleObject(name = "findings", summary = "A fresh check with a finding", value = VARIATION_RESULT),
                                            @ExampleObject(name = "cached", summary = "The same family again: the stored result",
                                                    value = VARIATION_RESULT_CACHED)})),
                    @ApiResponse(responseCode = "400", description = "Invalid request (code `invalid-request`) or unknown marketplace (`unknown-marketplace`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(name = "invalid-request", value = PROBLEM_INVALID_REQUEST))),
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
    public VariationCheckResult check(@Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                                      @Valid @RequestBody VariationCheckRequest request) {
        return service.check(client.id(), request);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Read a stored variation check",
            description = "Returns the stored result of an earlier variation check of the calling client while it is retained, in the "
                    + "same shape as the POST response (`cached` is false). After `expiresAt`, for an unknown id and for another "
                    + "client's check the answer is the same 404.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The stored result.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VariationCheckResult.class),
                                    examples = @ExampleObject(value = VARIATION_RESULT))),
                    @ApiResponse(responseCode = "400", description = "The id is not a UUID.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "404", description = "No retained variation check with this id for the calling client (code `variation-check-not-found`). A check of another client, an id that never existed and an expired check give the same answer.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_VARIATION_NOT_FOUND))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public VariationCheckResult get(@Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                                    @Parameter(description = "checkId returned by POST /api/v1/variation-checks") @PathVariable UUID id) {
        return service.get(client.id(), id);
    }
}
