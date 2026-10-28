package com.thanhdatpb.listingguard.api.check;

import static com.thanhdatpb.listingguard.api.docs.ApiExamples.AMAZON_REQUEST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.AMAZON_RESULT;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.ETSY_REQUEST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.ETSY_RESULT;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.ETSY_RESULT_CACHED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_INVALID_REQUEST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_NOT_FOUND;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_NOT_YET_SUPPORTED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RATE_LIMITED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_TOO_LARGE;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNAUTHORIZED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNKNOWN_MARKETPLACE;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checks")
@Tag(name = "Checks", description = "Check a listing and read stored results.")
public class CheckController {

    private static final String PROBLEM = "application/problem+json";

    private final CheckService service;

    public CheckController(CheckService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            summary = "Check a listing",
            description = """
                    Normalizes the listing, runs the marketplace's rules and stores the result. If the same listing content \
                    was already checked with the same analysisVersion, the stored result is returned instead and `cached` \
                    is true (no new check is created). The listing is the marketplace's own format; for Etsy it is the \
                    Open API v3 style JSON with snake_case fields, for Amazon the simple format with title, brand, \
                    bullet_points, description, search_terms, product_type, price, quantity and images.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CheckRequest.class),
                            examples = {
                                    @ExampleObject(name = "etsy-listing", summary = "An Etsy listing with problems",
                                            value = ETSY_REQUEST),
                                    @ExampleObject(name = "amazon-listing", summary = "An Amazon listing with problems",
                                            value = AMAZON_REQUEST)})),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The check result.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CheckResult.class),
                                    examples = {
                                            @ExampleObject(name = "findings", summary = "A fresh Etsy check with findings", value = ETSY_RESULT),
                                            @ExampleObject(name = "amazon-findings", summary = "A fresh Amazon check with findings", value = AMAZON_RESULT),
                                            @ExampleObject(name = "cached", summary = "The same listing again: stored result", value = ETSY_RESULT_CACHED)})),
                    @ApiResponse(responseCode = "400", description = "Invalid request (code `invalid-request`) or unknown marketplace (`unknown-marketplace`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class), examples = {
                                    @ExampleObject(name = "invalid-request", value = PROBLEM_INVALID_REQUEST),
                                    @ExampleObject(name = "unknown-marketplace", value = PROBLEM_UNKNOWN_MARKETPLACE)})),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "413", description = "The request body is over the size limit (256 KB).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_TOO_LARGE))),
                    @ApiResponse(responseCode = "415", description = "The body is not application/json (code `unsupported-media-type`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "The marketplace is planned but not supported yet (code `marketplace-not-yet-supported`). No marketplace is planned today.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_NOT_YET_SUPPORTED))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for this API key; see the Retry-After header.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public CheckResult check(@Valid @RequestBody CheckRequest request) {
        return service.check(request);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Read a stored check",
            description = "Returns the stored result of an earlier check, in the same shape as the POST response (`cached` is false).",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The stored check result.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CheckResult.class),
                                    examples = @ExampleObject(value = ETSY_RESULT))),
                    @ApiResponse(responseCode = "400", description = "The id is not a UUID.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "404", description = "No check with this id (code `check-not-found`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_NOT_FOUND))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for this API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public CheckResult get(@Parameter(description = "checkId returned by POST /api/v1/checks") @PathVariable UUID id) {
        return service.get(id);
    }
}
