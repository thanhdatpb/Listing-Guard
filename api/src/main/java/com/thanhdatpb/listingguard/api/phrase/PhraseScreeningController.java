package com.thanhdatpb.listingguard.api.phrase;

import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.security.ApiKeyFilter;
import com.thanhdatpb.listingguard.api.web.Problems;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/phrase-screenings")
@Tag(name = "Phrase screening", description = "Batch trademark reference warnings for keywords and print text.")
public class PhraseScreeningController {

    private static final String PROBLEM = "application/problem+json";
    private final PhraseScreeningService service;

    public PhraseScreeningController(PhraseScreeningService service) {
        this.service = service;
    }

    private static final String SYNTHETIC_RESULT = """
            {"analysisVersion":"abc123","marketplace":"ETSY","rulesetVersion":"trademark-risk-2026-12",
             "catalogVersion":"synthetic","catalogStatus":"SERVED","completeness":"COMPLETE",
             "summary":{"block":1,"review":0,"clear":0,"duplicates":0,"dropped":0,"fuzzySkipped":0},
             "phrases":[{"text":"Zorvex","searchVolume":1200,"verdict":"BLOCK","completeness":"COMPLETE","fuzzySkipped":false,
               "failedRuleIds":[],"skippedRuleIds":[],"findings":[{"ruleId":"catalog_exact","risk":"HIGH","source":"CATALOG",
               "mark":"Zorvex","matchType":"EXACT","riskAdjustments":[],"tier":"STRONG","ownerCountSameMark":1,
               "sourceIds":["uspto:12345680"],"sourceLinks":[{"sourceId":"uspto:12345680","label":"USPTO TSDR",
                 "url":"https://tsdr.uspto.gov/#caseNumber=12345680&caseSearchType=US_APPLICATION&caseType=DEFAULT&searchType=statusSearch"}],
               "field":"phrase","start":0,"end":6,"otherRuleIds":[],"matchedAlias":null}]}],
             "disclaimer":"Chỉ là cảnh báo tham khảo."}
            """;

    @PostMapping(consumes = "application/json", produces = "application/json")
    @Operation(operationId = "screenPhrases", summary = "Screen keywords and print text for trademark references",
            description = "Checks up to 2000 phrases in one batch. CLEAR describes the findings from checks that ran. "
                    + "Inspect completeness and the disclaimer before interpreting a result. Phrase text is not retained.")
    @ApiResponse(responseCode = "200", description = "Screening results and coverage.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PhraseScreeningResponse.class),
                    examples = @ExampleObject(name = "synthetic", value = SYNTHETIC_RESULT)))
    @ApiResponse(responseCode = "400", description = "Invalid request, marketplace or product type, or too many phrases.",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The plan does not include the trademark pack.",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Phrase screening is disabled.",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "The request is over the body size limit.",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "415", description = "The request body is not JSON.",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Marketplace is planned but not yet supported.",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Client rate limit, daily phrase limit or monthly quota exceeded.",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "No catalog or enabled watchlist, or processing budget exceeded.",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    public PhraseScreeningResponse screenPhrases(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @Valid @RequestBody PhraseScreeningRequest request) {
        return service.screen(client, request);
    }

    @ExceptionHandler(PhraseScreeningException.class)
    public ProblemDetail problem(PhraseScreeningException exception) {
        return Problems.of(exception.status, exception.code, exception.getMessage());
    }
}
