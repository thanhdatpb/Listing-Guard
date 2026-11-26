package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import java.util.List;

/** Inspect coverage and the disclaimer before interpreting a row marked CLEAR. */
public record PhraseScreeningResult(String analysisVersion, String marketplace, String rulesetVersion,
                                    String catalogVersion, TrademarkCatalogStatus catalogStatus,
                                    Completeness completeness, Summary summary, List<Row> phrases, String disclaimer) {
    public PhraseScreeningResult {
        phrases = Copies.list(phrases);
    }

    public enum Source { CATALOG, WATCHLIST, RULE, @JsonEnumDefaultValue UNRECOGNIZED }

    public enum Verdict { BLOCK, REVIEW, CLEAR, @JsonEnumDefaultValue UNRECOGNIZED }

    public record Summary(int block, int review, int clear, int duplicates, int dropped, int fuzzySkipped) {}

    public record Row(String text, Long searchVolume, Verdict verdict, Completeness completeness, boolean fuzzySkipped,
                      List<String> failedRuleIds, List<String> skippedRuleIds, List<Finding> findings) {
        public Row {
            failedRuleIds = Copies.list(failedRuleIds);
            skippedRuleIds = Copies.list(skippedRuleIds);
            findings = Copies.list(findings);
        }
    }

    public record Finding(String ruleId, RiskLevel risk, Source source, String mark, MatchType matchType,
                          List<RiskAdjustment> riskAdjustments, TrademarkCalibrationEvidence.Tier tier, Long ownerCountSameMark,
                          List<String> sourceIds, List<SourceLink> sourceLinks, String field, int start, int end,
                          List<String> otherRuleIds, String matchedAlias) {
        public Finding {
            riskAdjustments = Copies.list(riskAdjustments);
            sourceIds = Copies.list(sourceIds);
            sourceLinks = Copies.list(sourceLinks);
            otherRuleIds = Copies.list(otherRuleIds);
        }
    }
}
