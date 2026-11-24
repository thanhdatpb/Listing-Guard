package com.thanhdatpb.listingguard.api.review;

import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.security.ApiKeyFilter;
import com.thanhdatpb.listingguard.api.web.Problems;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/review-cases")
@ApiResponse(responseCode = "400", description = "Invalid request or pagination parameters.",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "Feature disabled or no accessible record at that address.",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "API request rate limit exceeded.",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ReviewCaseController {
    private final ReviewCaseService service;
    private final boolean enabled;

    public ReviewCaseController(ReviewCaseService service, @Value("${listing-guard.review-cases.enabled:false}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    public record CreateRequest(@NotBlank String pack, @NotNull UUID checkId, @NotNull @Min(0) Integer findingIndex) { }

    @GetMapping
    @Operation(operationId = "listReviewCases", summary = "List the calling client's review cases",
            description = "Filters use exact enum names. The cursor is scoped to the client, filters and sort. Limit is 1 to 100.")
    public ResponseEntity<ReviewCaseService.Page> list(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @RequestParam(required = false) ReviewCaseStatus status,
            @RequestParam(required = false) ReviewCase.Marketplace marketplace,
            @RequestParam(required = false) ReviewCase.Severity severity,
            @RequestParam(required = false) OffsetDateTime createdBefore,
            @RequestParam(required = false) OffsetDateTime createdAfter,
            @RequestParam(defaultValue = "NEWEST") ReviewCaseService.Sort sort,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String cursor) {
        requireEnabled();
        return response(service.list(client.id(), status, marketplace, severity, createdBefore, createdAfter, sort, limit, cursor));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getReviewCase", summary = "Read one review case and its evidence availability")
    public ResponseEntity<ReviewCaseService.Detail> get(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @PathVariable UUID id) {
        requireEnabled();
        return response(service.get(client.id(), id));
    }

    @PostMapping
    @Operation(operationId = "createReviewCase", summary = "Create or return a review case for one stored finding",
            description = "Requires a separately granted API key. Does not charge quota or change the underlying finding.")
    @ApiResponse(responseCode = "403", description = "The API key has no review write grant.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "Request body exceeds the API limit.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReviewCase> create(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @Valid @RequestBody CreateRequest request) {
        requireEnabled();
        return response(service.create(client, new FeedbackTarget(request.pack(), request.checkId(), request.findingIndex())));
    }

    @PostMapping("/{id}/decisions")
    @Operation(operationId = "recordReviewDecision", summary = "Record an authorized review decision",
            description = "Requires a separately granted API key and the expected revision. Replay with the same request ID and payload "
                    + "returns the original event. Decisions track internal work and are not legal approvals.")
    @ApiResponse(responseCode = "403", description = "The API key has no review write grant.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Stale revision, invalid transition, conflicting replay or unavailable evidence.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "Request body exceeds the API limit.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReviewAuditEvent> decide(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @PathVariable UUID id, @Valid @RequestBody ReviewDecision decision) {
        requireEnabled();
        return response(service.decide(client, id, decision));
    }

    @GetMapping("/{id}/events")
    @Operation(operationId = "listReviewEvents", summary = "List audit events in revision order",
            description = "Use nextAfterRevision as afterRevision to get the next page. Limit is 1 to 100.")
    public ResponseEntity<ReviewCaseService.AuditPage> events(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @PathVariable UUID id, @RequestParam(defaultValue = "-1") int afterRevision,
            @RequestParam(defaultValue = "25") int limit) {
        requireEnabled();
        return response(service.audit(client.id(), id, afterRevision, limit));
    }

    @ExceptionHandler(ReviewCaseException.class)
    public ResponseEntity<ProblemDetail> reviewProblem(ReviewCaseException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
                .body(Problems.of(error.status(), error.code(), error.getMessage()));
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ReviewCaseException(HttpStatus.NOT_FOUND, "review-cases-disabled", "Review cases are disabled.");
        }
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
