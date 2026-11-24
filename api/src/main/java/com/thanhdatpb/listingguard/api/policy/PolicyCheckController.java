package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.security.ApiKeyFilter;
import com.thanhdatpb.listingguard.api.web.Problems;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policy-checks")
@ApiResponse(responseCode = "400", description = "Invalid request.",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "Disabled, expired or no accessible policy check.",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Rate, plan quota or policy budget exhausted.",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class PolicyCheckController {
    private final PolicyChecks checks;

    public PolicyCheckController(PolicyChecks checks) {
        this.checks = checks;
    }

    @PostMapping
    @ApiResponse(responseCode = "200", description = "Policy analysis status and any verified excerpts.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PolicyChecks.Result.class)))
    @Operation(operationId = "createPolicyCheck", summary = "Retrieve verified policy evidence for manual review",
            description = "Opt-in Etsy analysis. A cache miss can call paid providers. Empty, partial and failed analyses never clear a listing.")
    @ApiResponse(responseCode = "403", description = "The plan does not include policy analysis.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Unsupported marketplace.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Analyzer busy.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PolicyChecks.Result> create(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @Valid @RequestBody PolicyCheckRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(checks.check(client.id(), request));
    }

    @GetMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "Retained policy check.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PolicyChecks.Result.class)))
    @Operation(operationId = "getPolicyCheck", summary = "Read a retained policy check for the calling client")
    public ResponseEntity<PolicyChecks.Result> get(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(checks.get(client.id(), id));
    }

    @ExceptionHandler(PolicyChecks.Failure.class)
    ResponseEntity<ProblemDetail> failure(PolicyChecks.Failure failure) {
        var response = ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore());
        if ("analyzer-busy".equals(failure.code())) {
            response.header("Retry-After", "5");
        }
        return response.body(Problems.of(failure.status(), failure.code(), failure.getMessage()));
    }
}
