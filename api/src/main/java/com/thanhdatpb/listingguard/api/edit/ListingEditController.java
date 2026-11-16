package com.thanhdatpb.listingguard.api.edit;

import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.docs.ApiExamples;
import com.thanhdatpb.listingguard.api.security.ApiKeyFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Additive edit comparison endpoint (ADR-033). */
@RestController
@RequestMapping("/api/v1/listing-edits")
@Tag(name = "Listing edits", description = "Compare the findings before and after a listing edit.")
public class ListingEditController {
    private static final String RESULT_EXAMPLE = """
            {"before":{"listingRules":{"checkId":"7b89c55e-a631-46f5-bffe-128044ff0b83","status":"COMPLETED","marketplace":"ETSY","rulesetVersion":"etsy-2026-12","analysisVersion":"etsy-2026-12","contentHash":"abc","cached":false,"createdAt":"2026-11-16T00:00:00Z","summary":{"errors":0,"warnings":0,"infos":0},"findings":[]},"trademarkRisk":{"checkId":"35a14ed8-0c12-421c-8b3a-1268d6090538","marketplace":"ETSY","rulesetVersion":"trademark-risk-2026-11b","catalogVersion":"synthetic","catalogStatus":"SERVED","analysisVersion":"abc","cached":false,"completeness":"COMPLETE","riskLevel":"LOW","failedRuleIds":[],"skippedRuleIds":[],"disclaimer":"Reference warning.","createdAt":"2026-11-16T00:00:00Z","expiresAt":"2026-12-16T00:00:00Z","findings":[]},"variationChecks":null},"after":{"listingRules":{"checkId":"408612ab-56ce-46a7-94ce-bc18ae67fe57","status":"COMPLETED","marketplace":"ETSY","rulesetVersion":"etsy-2026-12","analysisVersion":"etsy-2026-12","contentHash":"def","cached":false,"createdAt":"2026-11-16T00:00:00Z","summary":{"errors":0,"warnings":0,"infos":0},"findings":[]},"trademarkRisk":{"checkId":"3de67365-d5f4-4367-9810-c90a526e8453","marketplace":"ETSY","rulesetVersion":"trademark-risk-2026-11b","catalogVersion":"synthetic","catalogStatus":"SERVED","analysisVersion":"def","cached":false,"completeness":"COMPLETE","riskLevel":"LOW","failedRuleIds":[],"skippedRuleIds":[],"disclaimer":"Reference warning.","createdAt":"2026-11-16T00:00:00Z","expiresAt":"2026-12-16T00:00:00Z","findings":[]},"variationChecks":null},"changedFields":["title"],"newFindings":[],"modifiedFindings":[],"remainingFindings":[]}
            """;
    private final ListingEditService service;
    private final com.thanhdatpb.listingguard.api.history.CheckHistoryRecorder history;

    public ListingEditController(ListingEditService service,
                                 com.thanhdatpb.listingguard.api.history.CheckHistoryRecorder history) {
        this.service = service;
        this.history = history;
    }

    @PostMapping
    @Operation(operationId = "checkListingEdit", summary = "Compare a listing before and after an edit",
            description = "Runs the existing listing-rules and trademark-risk packs on both versions, including the client's active trademark watchlist. Optional variation families are checked when supplied. Underlying pack caches, plans and quotas apply. Listing text is not stored by this operation.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Before and after results and the finding comparison.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ListingEditResult.class),
                                    examples = @ExampleObject(name = "listing-edit-comparison", value = RESULT_EXAMPLE))),
                    @ApiResponse(responseCode = "400", description = "Invalid request or unknown marketplace.",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
                                    examples = {@ExampleObject(name = "invalid-request", value = ApiExamples.PROBLEM_INVALID_REQUEST),
                                            @ExampleObject(name = "unknown-marketplace", value = ApiExamples.PROBLEM_UNKNOWN_MARKETPLACE)})),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
                                    examples = @ExampleObject(value = ApiExamples.PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "403", description = "The client's plan does not include an analysis pack.",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
                                    examples = @ExampleObject(value = ApiExamples.PROBLEM_PLAN_NOT_ALLOWED))),
                    @ApiResponse(responseCode = "413", description = "The request body is over the API body limit.",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
                                    examples = @ExampleObject(value = ApiExamples.PROBLEM_TOO_LARGE))),
                    @ApiResponse(responseCode = "429", description = "Rate limited or monthly quota exceeded.",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class),
                                    examples = {@ExampleObject(name = "rate-limited", value = ApiExamples.PROBLEM_RATE_LIMITED),
                                            @ExampleObject(name = "quota-exceeded", value = ApiExamples.PROBLEM_QUOTA_EXCEEDED)}))})
    public ListingEditResult check(@RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                                   @Valid @RequestBody ListingEditRequest request) {
        ListingEditResult result = service.check(client.id(), request);
        record(client, result.before());
        record(client, result.after());
        return result;
    }

    private void record(AuthenticatedClient client, ListingEditResult.Side side) {
        history.api(client, com.thanhdatpb.listingguard.api.plan.Pack.LISTING_RULES.id(), side.listingRules().checkId(),
                side.listingRules().marketplace(), 1, null);
        history.api(client, com.thanhdatpb.listingguard.api.plan.Pack.TRADEMARK_RISK.id(), side.trademarkRisk().checkId(),
                side.trademarkRisk().marketplace(), 1, null);
        if (side.variationChecks() != null) {
            history.api(client, com.thanhdatpb.listingguard.api.plan.Pack.VARIATION_CHECKS.id(),
                    side.variationChecks().checkId(), side.variationChecks().marketplace(), 1, null);
        }
    }
}
