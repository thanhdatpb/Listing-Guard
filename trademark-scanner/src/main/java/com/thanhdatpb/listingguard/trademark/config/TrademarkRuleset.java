package com.thanhdatpb.listingguard.trademark.config;

import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.SourceLink;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizationSettings;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A parsed trademark ruleset: every threshold, word list, risk level and message of one version (ADR-001, ADR-009).
 * {@code contentHash} is the SHA-256 of the YAML text after line endings are normalized to LF and a BOM is dropped
 * (ADR-002). Every block of the file carries a {@code source} with status {@code unverified}; the loader checks it
 * and does not keep it, because these are internal heuristics and not a marketplace or legal rule.
 *
 * @param ruleOrder the rule ids in the order the file declares them: the order they run in and the tie-break
 *                  between equal risks
 * @param productClasses the optional {@code product_classes} section; {@link ProductClasses#NONE} when absent
 * @param genericWords   the optional {@code generic_words} section; {@link GenericWords#NONE} when absent
 * @param sourceLinks    the optional {@code source_links} section; {@link SourceLinks#NONE} when absent
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
        PureColor pureColor,
        ProductClasses productClasses,
        GenericWords genericWords,
        SourceLinks sourceLinks) {

    public static final String PACK_ID = "trademark-risk";

    /** The input fields a ruleset may ask the rules to scan. */
    public static final Set<String> SCANNABLE_FIELDS =
            Set.of("title", "tags", "bullet_points", "description", "brand", "shop_name", "search_terms",
                    "category");

    public TrademarkRuleset {
        ruleOrder = List.copyOf(ruleOrder);
    }

    /**
     * @param scanFields             input fields the rules read ({@code tags} and {@code bullet_points} stand for every item)
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

    /**
     * Product types and the Nice classes they relate to (ADR-009, "Product classes, source links and generic words").
     * A catalog match whose mark has Nice classes, none of them in the product type's list, drops by
     * {@code downgradeLevels}; a mark with no Nice class is never lowered. A ruleset without the section is
     * {@link #NONE}: it declares no product type, so every request that names one is refused.
     *
     * @param downgradeLevels how many levels such a match drops, never below LOW
     * @param types           product type key to Nice classes (1 to 45), in file order
     * @param note            the Vietnamese explanation added to such a finding
     */
    public record ProductClasses(int downgradeLevels, Map<String, List<Integer>> types, String note) {

        public static final ProductClasses NONE = new ProductClasses(0, Map.of(), "");

        public ProductClasses {
            Map<String, List<Integer>> copy = new LinkedHashMap<>();
            types.forEach((type, classes) -> copy.put(type, List.copyOf(classes)));
            types = Collections.unmodifiableMap(copy);
        }

        /** The product types the ruleset declares, in file order. */
        public List<String> knownTypes() {
            return List.copyOf(types.keySet());
        }

        /**
         * The Nice classes of a product type.
         *
         * @throws UnknownProductTypeException if the ruleset does not declare it
         */
        public Set<Integer> classesOf(String productType) {
            List<Integer> classes = types.get(productType);
            if (classes == null) {
                throw new UnknownProductTypeException(productType, knownTypes());
            }
            return Set.copyOf(classes);
        }
    }

    /**
     * Catalog marks that are also ordinary words, already normalized as a catalog mark is (tokens, one space apart).
     * A match of such a mark keeps its level only as an exact or phrase match in one of {@code contextFields}; anywhere
     * else it is at most {@code maxRiskWithoutContext}.
     */
    public record GenericWords(Set<String> words, List<String> contextFields, RiskLevel maxRiskWithoutContext,
                               String note) {

        public static final GenericWords NONE = new GenericWords(Set.of(), List.of(), RiskLevel.HIGH, "");

        public GenericWords {
            words = Set.copyOf(words);
            contextFields = List.copyOf(contextFields);
        }
    }

    /**
     * Public look-up pages for the source records of catalog marks. A stored source id is {@code <source_key>:<id>}; a
     * link is built only for a {@code source_key} the ruleset lists, from an id that matches that entry's pattern and is
     * made only of letters, digits, dot, underscore and hyphen, so no id can add a path, a query or a fragment.
     */
    public record SourceLinks(List<Template> templates) {

        public static final SourceLinks NONE = new SourceLinks(List.of());

        /** Characters an id may have whatever the entry's pattern says. */
        private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

        /**
         * @param sourceKey   the source key of the catalog manifest this entry is for
         * @param label       what the page is
         * @param idPattern   the whole id must match it
         * @param urlTemplate an https address with one {@code {id}}
         */
        public record Template(String sourceKey, String label, Pattern idPattern, String urlTemplate) {
        }

        public SourceLinks {
            templates = List.copyOf(templates);
        }

        /** The links of the ids the ruleset has a template for, in the order of the ids; the other ids are skipped. */
        public List<SourceLink> resolve(List<String> sourceIds) {
            List<SourceLink> links = new ArrayList<>();
            for (String sourceId : sourceIds) {
                int colon = sourceId.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = sourceId.substring(0, colon);
                String id = sourceId.substring(colon + 1);
                if (!SAFE_ID.matcher(id).matches()) {
                    continue;
                }
                for (Template template : templates) {
                    if (template.sourceKey().equals(key) && template.idPattern().matcher(id).matches()) {
                        links.add(new SourceLink(sourceId, template.label(),
                                template.urlTemplate().replace("{id}", URLEncoder.encode(id, StandardCharsets.UTF_8))));
                        break;
                    }
                }
            }
            return links;
        }
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
