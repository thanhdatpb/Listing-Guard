package com.thanhdatpb.listingguard.api.phrase;

import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult.CatalogStatus;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult.RiskAdjustmentView;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult.SourceLinkView;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.PhraseScreening.PhraseBatch;
import com.thanhdatpb.listingguard.trademark.PhraseScreening.PhraseSummary;
import com.thanhdatpb.listingguard.trademark.PhraseScreening.Source;
import com.thanhdatpb.listingguard.trademark.PhraseScreening.Verdict;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals.Tier;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PhraseScreeningResponse(String analysisVersion, String marketplace, String rulesetVersion,
                                      @Schema(nullable = true) String catalogVersion, CatalogStatus catalogStatus,
                                      Completeness completeness, PhraseSummary summary, List<PhraseRowView> phrases,
                                      String disclaimer) {

    static PhraseScreeningResponse of(String fingerprint, String marketplace, PhraseBatch result) {
        var rows = result.phrases().stream().map(row -> new PhraseRowView(row.text(), row.searchVolume(), row.verdict(),
                row.completeness(), row.fuzzySkipped(), row.failedRuleIds(), row.skippedRuleIds(),
                row.findings().stream().map(finding -> new PhraseFindingView(finding.ruleId(), finding.risk(),
                        finding.source(), finding.mark(), finding.matchType(),
                        finding.riskAdjustments().stream().map(RiskAdjustmentView::of).toList(),
                        finding.tier(), finding.ownerCountSameMark(), finding.sourceIds(),
                        finding.sourceLinks().stream().map(link -> new SourceLinkView(link.sourceId(), link.label(), link.url())).toList(),
                        finding.field(), finding.start(), finding.end(), finding.otherRuleIds(), finding.matchedAlias())).toList()
        )).toList();
        return new PhraseScreeningResponse(fingerprint, marketplace, result.rulesetVersion(), result.catalogVersion(),
                CatalogStatus.valueOf(result.catalogStatus().name()), result.completeness(), result.summary(), rows, result.disclaimer());
    }

    public record PhraseRowView(String text, @Schema(nullable = true) Long searchVolume, Verdict verdict,
                                Completeness completeness, boolean fuzzySkipped, List<String> failedRuleIds,
                                List<String> skippedRuleIds, List<PhraseFindingView> findings) {}

    public record PhraseFindingView(String ruleId, RiskLevel risk, Source source, @Schema(nullable = true) String mark,
                                    MatchType matchType, List<RiskAdjustmentView> riskAdjustments,
                                    @Schema(nullable = true) Tier tier, @Schema(nullable = true) Long ownerCountSameMark,
                                    List<String> sourceIds, List<SourceLinkView> sourceLinks, String field, int start, int end,
                                    List<String> otherRuleIds, @Schema(nullable = true) String matchedAlias) {}
}
