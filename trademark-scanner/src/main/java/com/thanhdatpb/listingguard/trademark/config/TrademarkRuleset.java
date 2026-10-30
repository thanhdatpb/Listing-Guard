package com.thanhdatpb.listingguard.trademark.config;

import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizationSettings;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * A parsed trademark ruleset: every threshold, word list, risk level and message of one version (ADR-001, ADR-009).
 * {@code contentHash} is the SHA-256 of the YAML text after line endings are normalized to LF and a BOM is dropped
 * (ADR-002). Every block of the file carries a {@code source} with status {@code unverified}; the loader checks it
 * and does not keep it, because these are internal heuristics and not a marketplace or legal rule.
 *
 * @param ruleOrder the rule ids in the order the file declares them: the order they run in and the tie-break
 *                  between equal risks
 */
public record TrademarkRuleset(
        String packId,
        String version,
        LocalDate checkedAt,
        String contentHash,
        String disclaimer,
        NormalizationSettings normalization,
        Matching matching,
        Lexicon lexicon,
        List<String> ruleOrder,
        CatalogRule catalogExact,
        CatalogRule catalogPhrase,
        CatalogRule catalogFuzzy,
        GeoSport geoSport,
        ColorContainsMark colorContainsMark,
        PureColor pureColor) {

    public static final String PACK_ID = "trademark-risk";

    /** The input fields a ruleset may ask the rules to scan. */
    public static final Set<String> SCANNABLE_FIELDS =
            Set.of("title", "tags", "description", "brand", "shop_name", "category");

    public TrademarkRuleset {
        ruleOrder = List.copyOf(ruleOrder);
    }

    /**
     * @param scanFields             input fields the rules read ({@code tags} stands for every tag)
     * @param maxNgramTokens         longest n-gram, in tokens, compared with the catalog
     * @param fuzzyMinChars          shortest gram (characters) that is compared fuzzily
     * @param fuzzyMaxChars          longest gram (characters) that is compared fuzzily
     * @param candidateThreshold     similarity the catalog uses to return candidates (pg_trgm in the database adapter)
     * @param finalThreshold         the Java score a candidate needs to become a fuzzy finding
     * @param scoringAlgorithmVersion the scoring algorithm the thresholds were tuned for; must be the one in the code
     * @param maxSnippetChars        longest text copied into evidence
     */
    public record Matching(List<String> scanFields, int maxNgramTokens, int fuzzyMinChars, int fuzzyMaxChars,
                           double candidateThreshold, double finalThreshold, String scoringAlgorithmVersion,
                           int maxSnippetChars) {
        public Matching {
            scanFields = List.copyOf(scanFields);
        }
    }

    /**
     * Word lists, already normalized: places and sport terms are token sequences, color and pattern words are single
     * tokens.
     */
    public record Lexicon(List<List<String>> places, List<List<String>> sportTerms, Set<String> colorWords,
                          Set<String> patternWords) {
        public Lexicon {
            places = places.stream().map(List::copyOf).toList();
            sportTerms = sportTerms.stream().map(List::copyOf).toList();
            colorWords = Set.copyOf(colorWords);
            patternWords = Set.copyOf(patternWords);
        }
    }

    /** Exact, phrase and fuzzy catalog rules. */
    public record CatalogRule(RiskLevel risk, String message, String suggestion) {
    }

    /** Place name and sport term close together. {@code maxTokenDistance} counts tokens; adjacent words are 1. */
    public record GeoSport(RiskLevel risk, int maxTokenDistance, String message, String suggestion) {
    }

    /** Explains a catalog match that lies inside a color phrase. It never changes the risk of the match. */
    public record ColorContainsMark(String note) {
    }

    /** A phrase of only color and pattern words with no catalog match; informational, always LOW. */
    public record PureColor(RiskLevel risk, int minTokens, String message, String suggestion) {
    }

    /** The settings of one of the three catalog rules. */
    public CatalogRule catalogRule(String ruleId) {
        return switch (ruleId) {
            case RuleIds.CATALOG_EXACT -> catalogExact;
            case RuleIds.CATALOG_PHRASE -> catalogPhrase;
            case RuleIds.CATALOG_FUZZY -> catalogFuzzy;
            default -> throw new IllegalArgumentException("Not a catalog rule: " + ruleId);
        };
    }

    /** Position of a rule in the declared order. */
    public int ruleIndex(String ruleId) {
        return ruleOrder.indexOf(ruleId);
    }

    /** The risk a rule assigns, or null for the rule that only annotates. */
    public RiskLevel riskOf(String ruleId) {
        return switch (ruleId) {
            case RuleIds.CATALOG_EXACT, RuleIds.CATALOG_PHRASE, RuleIds.CATALOG_FUZZY -> catalogRule(ruleId).risk();
            case RuleIds.GEO_SPORT_COMBINATION -> geoSport.risk();
            case RuleIds.PURE_COLOR_DESCRIPTION -> pureColor.risk();
            default -> null;
        };
    }
}
