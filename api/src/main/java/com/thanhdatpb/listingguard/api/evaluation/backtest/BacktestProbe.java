package com.thanhdatpb.listingguard.api.evaluation.backtest;

import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer.Analysis;
import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer.PartState;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.FindingSource;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Synthetic expectations are rollout gates, independent of claim labels and of legal outcomes. */
record BacktestProbe(int line, String id, RiskLevel min, RiskLevel max, RiskLevel actual, boolean passed,
                     String finding, DistinctivenessSignals.Tier tier, Long owners, Long serials,
                     RiskLevel namedMarkRisk, DistinctivenessSignals.Tier namedMarkTier, int findingCount, List<String> findingKeys) {

    static BacktestProbe assess(int line, String id, BacktestLabels.Labels labels, Analysis analysis) {
        var bounds = labels.bounds();
        if (bounds == null) {
            return null;
        }
        var result = analysis.trademarkState() == PartState.DONE ? analysis.trademark() : null;
        RiskLevel risk = result == null ? null : result.riskLevel();
        List<TrademarkFinding> findings = result == null ? List.of() : result.findings();
        Comparator<TrademarkFinding> byRisk = Comparator.comparing(TrademarkFinding::risk);
        var strongest = findings.stream().max(byRisk).orElse(null);
        var scanner = analysis.scanner();
        var normalizer = scanner == null ? null : new TextNormalizer(scanner.ruleset().normalization());
        String expected = normalizer == null || labels.mark() == null ? null : normalizer.normalizeMark(labels.mark());
        var named = expected == null ? null : findings.stream().filter(f -> f.evidence().matchedMark() != null
                && expected.equals(normalizer.normalizeMark(f.evidence().matchedMark()))).max(byRisk).orElse(null);
        var mainSignals = signals(analysis, normalizer, strongest);
        var namedSignals = signals(analysis, normalizer, named);
        boolean passed = result != null && result.completeness() == Completeness.COMPLETE && risk != null && risk.isDetermined()
                && (bounds.min() == null || risk.ordinal() >= bounds.min().ordinal())
                && (bounds.max() == null || risk.ordinal() <= bounds.max().ordinal());
        return new BacktestProbe(line, id, bounds.min(), bounds.max(), risk, passed, strongest == null ? null : strongest.ruleId(),
                mainSignals == null ? null : mainSignals.tier(), mainSignals == null ? null : mainSignals.ownerCountSameMark(),
                mainSignals == null ? null : mainSignals.sameMarkCount(), named == null ? null : named.risk(),
                namedSignals == null ? null : namedSignals.tier(), findings.size(), fingerprints(findings));
    }

    private static DistinctivenessSignals signals(Analysis analysis, TextNormalizer normalizer, TrademarkFinding finding) {
        if (finding == null || finding.source() != FindingSource.CATALOG || finding.evidence().matchedMark() == null
                || normalizer == null || analysis.scanner().catalog() == null) {
            return null;
        }
        String key = normalizer.normalizeMark(finding.evidence().matchedMark());
        var mark = analysis.scanner().catalog().findExact(List.of(key)).get(key);
        return mark == null ? null : mark.distinctiveness();
    }

    private static List<String> fingerprints(List<TrademarkFinding> findings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return findings.stream().map(f -> {
                var e = f.evidence();
                String identity = List.of(f.ruleId(), e.field(), Integer.toString(e.start()), Integer.toString(e.end()),
                        e.matchedMark() == null ? "" : e.matchedMark(), e.matchType().name(),
                        String.join(",", e.sourceIds().stream().sorted().toList())).toString();
                return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
            }).sorted().toList();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
