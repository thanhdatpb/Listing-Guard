package com.thanhdatpb.listingguard.api.plan;

import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RATE_LIMITED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNAUTHORIZED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.USAGE_RESULT;

import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.security.ApiKeyFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The calling client's plan and use of the month (ADR-013). Reading it is never refused for plan or quota and costs no unit. */
@RestController
@RequestMapping("/api/v1/usage")
@Tag(name = "Usage", description = "The calling client's plan and monthly quota.")
public class UsageController {

    private static final String PROBLEM = "application/problem+json";

    private final PlanService plans;

    public UsageController(PlanService plans) {
        this.plans = plans;
    }

    @GetMapping
    @Operation(
            summary = "Read the plan and the monthly usage of the calling client",
            description = """
                    Returns the client's plan, its quota per UTC month, how much of the current month is used and when it \
                    resets. One unit is one new result that is stored, per endpoint: a result answered from the cache \
                    (`cached: true`) costs nothing, and a full check of one listing (rules and trademark) is two units. \
                    `enforced` is false on a deployment where plans are off; then nothing is limited, `monthlyQuota` and \
                    `remaining` are null and `packs` lists every pack. This call is never refused for plan or quota and \
                    costs no unit.""",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The plan and the usage of the current UTC month.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Usage.class),
                                    examples = @ExampleObject(value = USAGE_RESULT))),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public Usage usage(@Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client) {
        return plans.usage(client.id());
    }
}
