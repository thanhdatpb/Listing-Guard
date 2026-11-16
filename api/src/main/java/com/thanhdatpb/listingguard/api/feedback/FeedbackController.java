package com.thanhdatpb.listingguard.api.feedback;

import static com.thanhdatpb.listingguard.api.docs.ApiExamples.FEEDBACK_REQUEST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.FEEDBACK_RESULT;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.FEEDBACK_STATS;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_FINDING_NOT_FOUND;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_INVALID_FEEDBACK;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_INVALID_REPORT_PERIOD;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_INVALID_REQUEST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_NOT_YET_SUPPORTED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RATE_LIMITED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_REPORT_PERIOD_TOO_LONG;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_TOO_LARGE;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNAUTHORIZED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNKNOWN_MARKETPLACE;

import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.report.ReportQuery;
import com.thanhdatpb.listingguard.api.security.ApiKeyFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verdicts on findings and their statistics, for the calling client (ADR-034). The reviewer of a {@code POST} is the API key the
 * request came with. Neither operation needs a plan pack or costs a unit (ADR-013); neither changes a finding or a result.
 */
@RestController
@RequestMapping("/api/v1/feedback")
@Tag(name = "Feedback", description = "Verdicts on stored findings (correct, false positive, not useful) and their statistics.")
public class FeedbackController {

    private static final String PROBLEM = "application/problem+json";

    private final FindingFeedbackService feedback;
    private final FeedbackStatsService stats;

    public FeedbackController(FindingFeedbackService feedback, FeedbackStatsService stats) {
        this.feedback = feedback;
        this.stats = stats;
    }

    @PostMapping
    @Operation(
            operationId = "recordFeedback",
            summary = "Give a verdict on one finding of a stored result",
            description = """
                    Records what a person thought of one finding: `CORRECT`, `FALSE_POSITIVE` or `NOT_USEFUL`, with an optional note \
                    of at most 280 characters (do not paste listing text into it). The finding is addressed by the pack, the \
                    `checkId` of the stored result and its index in that result's `findings`, in the order GET returns them.

                    One verdict per API key per finding: sending another one replaces the verdict and the note (`created` is then \
                    false). Only a finding of the calling client's own results can be answered; an unknown result, another \
                    client's, a trademark or variation result past its retention, and an index outside the result are all \
                    `404 finding-not-found`.

                    It changes no finding, result, rule or cache, needs no plan pack and costs no unit of the quota. Nothing of the \
                    listing is stored: only the address, the rule id, the source, the level and the analysisVersion of the finding.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = FeedbackRequest.class),
                    examples = @ExampleObject(name = "false-positive", value = FEEDBACK_REQUEST))),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The verdict as stored.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = FeedbackResult.class),
                                    examples = @ExampleObject(name = "stored", value = FEEDBACK_RESULT))),
                    @ApiResponse(responseCode = "400", description = "The body is not valid (code `invalid-request`), or the pack, the "
                            + "verdict or the note cannot be stored (code `invalid-feedback`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class), examples = {
                                    @ExampleObject(name = "invalid-request", value = PROBLEM_INVALID_REQUEST),
                                    @ExampleObject(name = "invalid-feedback", value = PROBLEM_INVALID_FEEDBACK)})),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "404", description = "No finding of the calling client at that address (code `finding-not-found`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_FINDING_NOT_FOUND))),
                    @ApiResponse(responseCode = "413", description = "The body is larger than the limit.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_TOO_LARGE))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public FeedbackResult recordFeedback(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @Valid @RequestBody FeedbackRequest request) {
        Reviewer reviewer = feedback.apiKeyReviewer(client.id(), client.keyId());
        return FeedbackResult.of(feedback.record(client.id(), reviewer, request.pack(), request.checkId(), request.findingIndex(),
                request.verdict(), request.note()));
    }

    @GetMapping("/stats")
    @Operation(
            operationId = "getFeedbackStats",
            summary = "Read the verdicts of the calling client per rule and per reviewer over a period",
            description = """
                    Counts the verdicts given in the period (by when each was last given) per verdict, per rule (with the findings \
                    of that rule in the results created in the period, to compare with) and per reviewer. A rate is computed from \
                    verdicts only: findings nobody judged are not counted as correct. It is what reviewers reported, not a measured \
                    precision. No note and no text of a listing is returned; a reviewer is given by kind and id, never an e-mail.

                    `from` and `to` are UTC dates, both included, both required, with the limits of `GET /api/v1/reports/summary`. \
                    Read only, no plan pack, no unit of the quota, `Cache-Control: no-store`.""",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The statistics.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = FeedbackStats.class),
                                    examples = @ExampleObject(name = "four-weeks", value = FEEDBACK_STATS))),
                    @ApiResponse(responseCode = "400", description = "`from` or `to` is missing, malformed or reversed (code `invalid-report-period`), "
                            + "the period is longer than the limit (code `report-period-too-long`), or the marketplace is unknown "
                            + "(code `unknown-marketplace`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class), examples = {
                                    @ExampleObject(name = "invalid-report-period", value = PROBLEM_INVALID_REPORT_PERIOD),
                                    @ExampleObject(name = "report-period-too-long", value = PROBLEM_REPORT_PERIOD_TOO_LONG),
                                    @ExampleObject(name = "unknown-marketplace", value = PROBLEM_UNKNOWN_MARKETPLACE)})),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "422", description = "The marketplace is planned but not supported yet (code `marketplace-not-yet-supported`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_NOT_YET_SUPPORTED))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public ResponseEntity<FeedbackStats> feedbackStats(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @Parameter(description = "The first day, a UTC date written as `YYYY-MM-DD`.", required = true, example = "2026-10-20")
            @RequestParam(required = false) String from,
            @Parameter(description = "The last day, included, a UTC date written as `YYYY-MM-DD`.", required = true, example = "2026-11-16")
            @RequestParam(required = false) String to,
            @Parameter(description = "`ETSY` or `AMAZON`, case-insensitive. Optional: without it every marketplace is counted.", example = "ETSY")
            @RequestParam(required = false) String marketplace) {
        ReportQuery query = stats.query(from, to, marketplace);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stats.stats(client.id(), query));
    }
}
