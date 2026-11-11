package com.thanhdatpb.listingguard.api.capabilities;

import static com.thanhdatpb.listingguard.api.docs.ApiExamples.CAPABILITIES;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RATE_LIMITED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNAUTHORIZED;

import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment serves (ADR-017): the packs and their ruleset versions, the trademark catalog's state, whether plans and
 * fixes are on. Read only: nothing is stored and no plan pack or quota is involved (ADR-013). It needs a client API key like
 * every endpoint (ADR-007), and the answer is the same for every client.
 */
@RestController
@RequestMapping("/api/v1/capabilities")
@Tag(name = "Capabilities", description = "What this deployment serves.")
public class CapabilitiesController {

    private static final String PROBLEM = "application/problem+json";

    /** It changes only with a deployment or a restart; five minutes of staleness is harmless (as for the ruleset limits). */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();

    private final CapabilitiesService service;

    public CapabilitiesController(CapabilitiesService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(
            summary = "Read what this deployment serves",
            description = """
                    Returns the analysis packs this deployment serves with the version of the active ruleset per marketplace, \
                    the state of the trademark catalog (`READY`, `DISABLED` or `REFUSED`, and whether it is the DEMO catalog), \
                    whether plans and the monthly quota are applied and whether checks return fix suggestions. When the \
                    trademark catalog is not `READY` the risk of a listing is unknown, never low. It says nothing about the \
                    calling client: use `GET /api/v1/usage` for its plan and quota. It holds no secret setting and no catalog \
                    name. The answer is the same for every client, carries an `ETag` and may be cached for five minutes; it \
                    costs no quota and needs no plan pack.""",
            responses = {
                    @ApiResponse(responseCode = "200", description = "What the deployment serves.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Capabilities.class),
                                    examples = @ExampleObject(value = CAPABILITIES))),
                    @ApiResponse(responseCode = "304", description = "The `If-None-Match` header matches the `ETag`: nothing changed, there is no body."),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public ResponseEntity<Capabilities> capabilities() {
        Capabilities result = service.capabilities();
        return ResponseEntity.ok().cacheControl(CACHE).eTag(etag(result)).body(result);
    }

    /** Identifies what the body says, so it changes exactly when the answer does. */
    private static String etag(Capabilities result) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(result.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
