package com.thanhdatpb.listingguard.api.report;

import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_INVALID_REPORT_PERIOD;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_NOT_YET_SUPPORTED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RATE_LIMITED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_REPORT_PERIOD_TOO_LONG;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNAUTHORIZED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNKNOWN_MARKETPLACE;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.REPORT_SUMMARY;

import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.security.ApiKeyFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The listing health report of the calling client (ADR-018): counts of its stored results over a period. Read only. It needs
 * no plan pack and costs no unit: it returns the client's own stored numbers and runs no analysis (ADR-013). The client is
 * the owner of the API key; there is no way to name another one (ADR-007).
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Counts of the calling client's stored results over a period.")
public class ReportsController {

    private static final String PROBLEM = "application/problem+json";

    private final HealthReportService service;

    public ReportsController(HealthReportService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    @Operation(
            operationId = "getReportSummary",
            summary = "Read the health report of the calling client over a period",
            description = """
                    Adds up what the client has stored: results per pack, the rules with the most findings, the trademark risk \
                    levels and the catalog marks matched most often, and the same numbers per ISO week. It counts; it never returns \
                    or reads text a client typed (a listing, a tag, a buyer's text): only counts, enumerations, rule ids, catalog \
                    marks and version strings.

                    `from` and `to` are UTC dates, both included, and both are required. The longest period is set by the \
                    deployment (92 days by default). A period with no data is a `200` with zeros.

                    A "check" is a stored result: a cached answer creates no row, so a listing checked again with the same \
                    analysis is counted once, and there is no cache-hit number. The packs `trademark-risk`, `variation-checks` and \
                    `personalization-screening` delete their results after a retention period and count only what is still stored; \
                    `coverageNote` says from which day their numbers are complete. `listing-rules` results are not deleted.

                    It is read only, needs no plan pack, costs no unit of the quota and is never cached (`Cache-Control: \
                    no-store`).""",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The report.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = HealthReport.class),
                                    examples = @ExampleObject(name = "week-report", summary = "A week", value = REPORT_SUMMARY))),
                    @ApiResponse(responseCode = "400", description = "`from` or `to` is missing, malformed or reversed (code `invalid-report-period`), "
                            + "the period is longer than the limit (code `report-period-too-long`, with `maxDays` and `requestedDays`), "
                            + "or the marketplace is unknown (code `unknown-marketplace`).",
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
    public ResponseEntity<HealthReport> summary(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @Parameter(description = "The first day, a UTC date written as `YYYY-MM-DD`.", required = true, example = "2026-11-02")
            @RequestParam(required = false) String from,
            @Parameter(description = "The last day, included, a UTC date written as `YYYY-MM-DD`.", required = true, example = "2026-11-08")
            @RequestParam(required = false) String to,
            @Parameter(description = "`ETSY` or `AMAZON`, case-insensitive. Optional: without it every marketplace is counted.", example = "ETSY")
            @RequestParam(required = false) String marketplace) {
        ReportQuery query = service.query(from, to, marketplace);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summary(client.id(), query));
    }
}
