package com.thanhdatpb.listingguard.api.edit;

import io.swagger.v3.oas.annotations.media.Schema;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.variation.VariationCheckResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The comparison of two retained pack analyses. No listing content is stored by this response model. */
public record ListingEditResult(
        Side before,
        Side after,
        List<String> changedFields,
        List<ComparedFinding> newFindings,
        List<ComparedFinding> modifiedFindings,
        List<ComparedFinding> remainingFindings) {

    @Schema(name = "ListingEditSide")
    public record Side(CheckResult listingRules, TrademarkCheckResult trademarkRisk,
                       @Schema(nullable = true) VariationCheckResult variationChecks) {
    }

    /** Finding JSON is kept pack-specific and identical to that pack's existing finding response. */
    @Schema(name = "ListingEditComparedFinding")
    public record ComparedFinding(String pack, @Schema(nullable = true) Map<String, Object> before,
                                  @Schema(nullable = true) Map<String, Object> after,
                                  FeedbackTarget feedbackTarget) {
        public static FeedbackTarget target(String pack, UUID id, int position) {
            return new FeedbackTarget(pack, id, position);
        }
    }
}
