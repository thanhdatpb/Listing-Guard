package com.thanhdatpb.listingguard.api.ruleset;

import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RATE_LIMITED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RULESET_NOT_FOUND;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNAUTHORIZED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.RULESET_LIMITS_ETSY;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The numeric limits of the ruleset that checks listings, so a client can show a character counter that follows the rules
 * instead of copying their numbers. Read only: nothing is stored, nothing is counted against a plan or a quota (ADR-013), and
 * no pack is needed. It needs a client API key like every endpoint (ADR-007).
 */
@RestController
@RequestMapping("/api/v1/rulesets")
@Tag(name = "Rulesets", description = "The limits of the rules that check a listing.")
public class RulesetLimitsController {

    private static final String PROBLEM = "application/problem+json";

    /** A limit changes only with a new ruleset version, which needs a deployment; five minutes of staleness is harmless. */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();

    private final RulesetLimitsService service;

    public RulesetLimitsController(RulesetLimitsService service) {
        this.service = service;
    }

    @GetMapping("/{marketplace}/limits")
    @Operation(
            summary = "Read the limits of a marketplace's ruleset",
            description = """
                    Returns the numeric limits of the ruleset that `POST /api/v1/checks` applies to the marketplace, each with the \
                    field it applies to, the rule that enforces it, what it counts (`unit`), its severity, how well it is sourced \
                    and whether it is enabled. Every number comes from the loaded ruleset, so it is the number the check uses; \
                    use it for a character counter instead of copying the numbers. `rulesetVersion` is the version of the \
                    ruleset; when it changes, read the limits again.

                    For Amazon, `productType` applies that product type's override of the ruleset, as the check does; a product \
                    type with no override, and any product type for Etsy, gets the defaults. A rule that is switched off is \
                    listed with `enabled: false`. The answer is the same for every client, carries an `ETag` and may be cached \
                    for five minutes; it costs no quota and needs no plan pack. Call it from your backend: the API key must not \
                    be put in browser code.""",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The limits of the ruleset.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RulesetLimitsResult.class),
                                    examples = @ExampleObject(name = "etsy-limits", summary = "The Etsy limits", value = RULESET_LIMITS_ETSY))),
                    @ApiResponse(responseCode = "304", description = "The `If-None-Match` header matches the `ETag`: nothing changed, there is no body."),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "404", description = "No ruleset is active for the marketplace (code `ruleset-not-found`); `detail` lists the supported ones.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RULESET_NOT_FOUND))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public ResponseEntity<RulesetLimitsResult> limits(
            @Parameter(description = "`ETSY` or `AMAZON`, case-insensitive.", example = "ETSY") @PathVariable String marketplace,
            @Parameter(description = "Amazon product type whose override applies, for example `LUGGAGE`; matched ignoring case. "
                    + "Optional: without it, or with a product type that has no override, the defaults are returned.")
            @RequestParam(required = false) String productType) {
        RulesetLimitsResult result = RulesetLimitsResult.of(service.limits(marketplace, productType));
        return ResponseEntity.ok().cacheControl(CACHE).eTag(etag(result)).body(result);
    }

    /**
     * Identifies what the body says: the ruleset version, the marketplace, the product type and every limit, so it changes
     * exactly when the answer does (a new ruleset version, another product type, or a rule that declares another limit).
     */
    private static String etag(RulesetLimitsResult result) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(result.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
