package com.thanhdatpb.listingguard.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals.Tier;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import com.thanhdatpb.listingguard.trademark.rules.ScanContext;
import com.thanhdatpb.listingguard.trademark.rules.WatchlistMatcher;
import com.thanhdatpb.listingguard.trademark.watchlist.Watchlist;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request-scoped lookup and screening for independent short phrases. */
public final class PhraseScreening {
    public static final String VERSION = "1";
    public static final int MAX_LENGTH = 200;
    private static final int FUZZY_CANDIDATE_BUDGET = 25_000;

    private PhraseScreening() {
    }

    public enum Verdict { BLOCK, REVIEW, CLEAR }
    public enum Source { CATALOG, WATCHLIST, RULE }
    public enum CatalogStatus { SERVED, NOT_CONFIGURED }

    public record Input(String text, Long searchVolume) {}

    public record PhraseFinding(String ruleId, RiskLevel risk, Source source, String mark, MatchType matchType,
                          List<RiskAdjustment> riskAdjustments, Tier tier, Long ownerCountSameMark,
                          List<String> sourceIds, List<SourceLink> sourceLinks, String field, int start, int end,
                          List<String> otherRuleIds, String matchedAlias) {}

    public record PhraseRow(String text, Long searchVolume, Verdict verdict, Completeness completeness, boolean fuzzySkipped,
                      List<String> failedRuleIds, List<String> skippedRuleIds, List<PhraseFinding> findings) {}

    public record PhraseSummary(int block, int review, int clear, int duplicates, int dropped, int fuzzySkipped) {}

    public record PhraseBatch(List<PhraseRow> phrases, PhraseSummary summary, Completeness completeness, CatalogStatus catalogStatus,
                         String catalogVersion, String rulesetVersion, String disclaimer) {}

    public static PhraseBatch screen(TrademarkScanner scanner, List<Input> inputs, String productType, Watchlist watchlist,
                                int maxFuzzyPhrases) {
        var ruleset = scanner.ruleset();
        String type = CanonicalInput.canonical(productType);
        Set<Integer> classes = type.isEmpty() ? null : ruleset.productClasses().classesOf(type);
        var normalizer = new TextNormalizer(ruleset.normalization());
        var backend = scanner.catalog();

        Map<String, Input> unique = new LinkedHashMap<>();
        int dropped = 0;
        for (Input input : inputs) {
            String raw = input.text();
            if (raw == null || raw.codePointCount(0, raw.length()) > MAX_LENGTH) {
                dropped++;
                continue;
            }
            String text = CanonicalInput.canonical(raw);
            String key = normalizer.normalizeMark(text);
            if (key.isEmpty()) {
                dropped++;
                continue;
            }
            unique.merge(key, new Input(text, input.searchVolume()), PhraseScreening::mergeVolume);
        }

        List<Input> phrases = List.copyOf(unique.values());
        Set<Integer> fuzzyRows = new LinkedHashSet<>();
        if (backend != null) {
            var ranked = new ArrayList<Integer>(phrases.size());
            for (int row = 0; row < phrases.size(); row++) {
                ranked.add(row);
            }
            ranked.sort(Comparator.comparing(row -> phrases.get(row).searchVolume(),
                    Comparator.nullsLast(Comparator.reverseOrder())));
            fuzzyRows.addAll(ranked.subList(0, Math.min(maxFuzzyPhrases, ranked.size())));
        }

        List<CanonicalInput> canonical = phrases.stream().map(input -> CanonicalInput.of(
                new TrademarkCheckInput(null, List.of(input.text()), List.of(), null, null, null, null, null, type))).toList();
        var exactForms = new LinkedHashSet<String>();
        var fuzzyForms = new LinkedHashSet<String>();
        if (backend != null) {
            for (int row = 0; row < canonical.size(); row++) {
                var context = ScanContext.create(ruleset, backend, normalizer, canonical.get(row),
                        type.isEmpty() ? null : type, classes);
                context.lookupForms(false).forEach(exactForms::add);
                if (fuzzyRows.contains(row)) {
                    context.lookupForms(true).forEach(fuzzyForms::add);
                }
            }
        }
        var exact = exactForms.isEmpty() ? Map.<String, CatalogMark>of() : backend.findExact(exactForms);
        fuzzyForms.removeAll(exact.keySet());
        var fuzzy = fuzzyForms.isEmpty() ? Map.<String, List<CatalogMark>>of()
                : backend.fuzzyBatchCandidates(fuzzyForms, ruleset.matching().candidateThreshold(), FUZZY_CANDIDATE_BUDGET);
        var marks = new HashMap<>(exact);
        fuzzy.values().forEach(matches -> matches.forEach(mark -> marks.putIfAbsent(mark.normalized(), mark)));
        TrademarkCatalog exactOnly = backend == null ? null : new BatchLookup(backend, exact, Map.of());
        TrademarkCatalog full = backend == null ? null : new BatchLookup(backend, exact, fuzzy);

        var watchlistCandidates = WatchlistMatcher.prepare(watchlist, normalizer);
        var rows = new ArrayList<PhraseRow>(phrases.size());
        int blocked = 0;
        int review = 0;
        for (int row = 0; row < phrases.size(); row++) {
            Input input = phrases.get(row);
            boolean fuzzySkipped = backend != null && !fuzzyRows.contains(row);
            TrademarkResult result = scanner.scan(canonical.get(row), watchlist, fuzzySkipped ? exactOnly : full,
                    watchlistCandidates);
            List<PhraseFinding> findings = result.findings().stream()
                    .map(finding -> toFinding(finding, marks, normalizer)).toList();
            Verdict verdict = findings.stream().anyMatch(f -> f.source() == Source.WATCHLIST || f.risk() == RiskLevel.HIGH)
                    ? Verdict.BLOCK : findings.stream().anyMatch(f -> f.risk() == RiskLevel.MEDIUM)
                    ? Verdict.REVIEW : Verdict.CLEAR;
            blocked += verdict == Verdict.BLOCK ? 1 : 0;
            review += verdict == Verdict.REVIEW ? 1 : 0;
            rows.add(new PhraseRow(input.text(), input.searchVolume(), verdict,
                    fuzzySkipped ? Completeness.INCOMPLETE : result.completeness(), fuzzySkipped,
                    result.failedRuleIds(), result.skippedRuleIds(), findings));
        }

        int skipped = backend == null ? 0 : phrases.size() - fuzzyRows.size();
        var summary = new PhraseSummary(blocked, review, rows.size() - blocked - review,
                inputs.size() - dropped - rows.size(), dropped, skipped);
        Completeness completeness = backend == null || rows.stream().anyMatch(row -> row.completeness() == Completeness.INCOMPLETE)
                ? Completeness.INCOMPLETE : Completeness.COMPLETE;
        return new PhraseBatch(List.copyOf(rows), summary, completeness, backend == null ? CatalogStatus.NOT_CONFIGURED : CatalogStatus.SERVED,
                backend == null ? null : backend.version(), ruleset.version(), ruleset.disclaimer());
    }

    private static Input mergeVolume(Input first, Input next) {
        if (next.searchVolume() != null && (first.searchVolume() == null || next.searchVolume() > first.searchVolume())) {
            return new Input(first.text(), next.searchVolume());
        }
        return first;
    }

    private static PhraseFinding toFinding(TrademarkFinding finding, Map<String, CatalogMark> marks, TextNormalizer normalizer) {
        Evidence original = finding.evidence();
        Source source = original.watchlist() != null ? Source.WATCHLIST
                : original.matchedMark() != null ? Source.CATALOG : Source.RULE;
        CatalogMark mark = source == Source.CATALOG ? marks.get(normalizer.normalizeMark(original.matchedMark())) : null;
        var signals = mark == null ? null : mark.distinctiveness();
        return new PhraseFinding(finding.ruleId(), finding.risk(), source, original.matchedMark(), original.matchType(),
                original.riskAdjustments(), signals == null ? null : signals.tier(),
                signals == null ? null : signals.ownerCountSameMark(), original.sourceIds(), original.sourceLinks(),
                "phrase", original.start(), original.end(), original.otherRuleIds(),
                original.watchlist() == null ? null : original.watchlist().matchedAlias());
    }

    private record BatchLookup(TrademarkCatalog backend, Map<String, CatalogMark> exact,
                               Map<String, List<CatalogMark>> fuzzy) implements TrademarkCatalog {
        @Override
        public String version() {
            return backend.version();
        }

        @Override
        public Map<String, CatalogMark> findExact(Collection<String> forms) {
            Map<String, CatalogMark> found = new HashMap<>();
            forms.forEach(form -> {
                CatalogMark mark = exact.get(form);
                if (mark != null) {
                    found.put(form, mark);
                }
            });
            return found;
        }

        @Override
        public Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> forms, double threshold) {
            return fuzzy;
        }
    }
}
