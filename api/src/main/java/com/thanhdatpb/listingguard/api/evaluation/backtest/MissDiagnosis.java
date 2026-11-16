package com.thanhdatpb.listingguard.api.evaluation.backtest;

import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.match.Similarity;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Explains why the trademark scan did not catch a claim that named a mark (ADR-032). It asks the scanner's own catalog and
 * normalizer, after the scan, and never changes the scan: the scanner's findings are the result, this is only their
 * explanation for a person reading the report.
 *
 * <p>The checks run in a fixed order ({@link MissReason}): the scanner found the mark (and at what level), the mark is not
 * in the catalog, its words are in the text, a near spelling is in the text, none of these.
 */
final class MissDiagnosis {

    /** A reason with its short detail (no listing text but the one near word, cut). */
    record Diagnosis(MissReason reason, String detail) {
    }

    private static final int TOKEN_SHOWN = 40;

    private MissDiagnosis() {
    }

    /** The normalized form of a labelled mark, as the scanner's catalog keys it. */
    static String normalized(TrademarkScanner scanner, String mark) {
        return new TextNormalizer(scanner.ruleset().normalization()).normalizeMark(mark);
    }

    /** True when some finding names the labelled mark at the catch level or above. */
    static boolean matchedAtLevel(TrademarkScanner scanner, TrademarkResult result, String mark, RiskLevel catchRisk) {
        String wanted = normalized(scanner, mark);
        return findingsFor(scanner, result, wanted).stream()
                .anyMatch(finding -> finding.risk().ordinal() >= catchRisk.ordinal());
    }

    /** Why the scan of a listing whose claim named {@code mark} did not catch it. */
    static Diagnosis trademark(TrademarkScanner scanner, TrademarkResult result, TrademarkCheckInput input, String mark,
                               double variantThreshold) {
        String wanted = normalized(scanner, mark);
        List<TrademarkFinding> found = findingsFor(scanner, result, wanted);
        if (!found.isEmpty()) {
            List<String> adjustments = found.stream().flatMap(finding -> finding.evidence().riskAdjustments().stream())
                    .map(adjustment -> adjustment.reason().name().toLowerCase(Locale.ROOT) + " " + adjustment.from() + "->"
                            + adjustment.to())
                    .distinct().toList();
            RiskLevel highest = RiskLevel.highest(found.stream().map(TrademarkFinding::risk).toList());
            if (!adjustments.isEmpty()) {
                return new Diagnosis(MissReason.LEVEL_LOWERED, "mức " + highest + "; " + String.join(", ", adjustments));
            }
            return new Diagnosis(MissReason.BELOW_THRESHOLD, "mức " + highest);
        }
        if (wanted.isEmpty() || scanner.catalog().findExact(List.of(wanted)).isEmpty()) {
            return new Diagnosis(MissReason.MARK_NOT_IN_CATALOG, "catalog " + result.catalogVersion());
        }
        TextNormalizer normalizer = new TextNormalizer(scanner.ruleset().normalization());
        int size = wanted.split(" ").length;
        String best = null;
        double bestScore = 0;
        for (String text : texts(input)) {
            List<String> tokens = normalizer.tokenTexts(text);
            for (int i = 0; i + size <= tokens.size(); i++) {
                String gram = String.join(" ", tokens.subList(i, i + size));
                if (gram.equals(wanted)) {
                    return new Diagnosis(MissReason.NOT_MATCHED, null);
                }
                double score = Similarity.score(gram, wanted);
                if (score > bestScore) {
                    bestScore = score;
                    best = gram;
                }
            }
        }
        if (best != null && bestScore >= variantThreshold) {
            return new Diagnosis(MissReason.SPELLING_VARIANT, String.format(Locale.ROOT, "từ gần nhất '%s' (độ giống %.2f)",
                    clip(best), bestScore));
        }
        return new Diagnosis(MissReason.MARK_NOT_IN_TEXT, null);
    }

    private static List<TrademarkFinding> findingsFor(TrademarkScanner scanner, TrademarkResult result, String wanted) {
        if (wanted.isEmpty()) {
            return List.of();
        }
        return result.findings().stream()
                .filter(finding -> finding.evidence() != null && finding.evidence().matchedMark() != null)
                .filter(finding -> normalized(scanner, finding.evidence().matchedMark()).equals(wanted))
                .collect(Collectors.toList());
    }

    /** The texts the scanner input holds, the same fields the scanner reads (a field the ruleset does not scan included). */
    private static List<String> texts(TrademarkCheckInput input) {
        List<String> texts = new ArrayList<>();
        add(texts, input.title());
        input.tags().forEach(tag -> add(texts, tag));
        input.bulletPoints().forEach(bullet -> add(texts, bullet));
        add(texts, input.description());
        add(texts, input.brand());
        add(texts, input.searchTerms());
        return texts;
    }

    private static void add(List<String> texts, String text) {
        if (text != null && !text.isBlank()) {
            texts.add(text);
        }
    }

    private static String clip(String value) {
        return value.length() <= TOKEN_SHOWN ? value : value.substring(0, TOKEN_SHOWN) + "...";
    }
}
