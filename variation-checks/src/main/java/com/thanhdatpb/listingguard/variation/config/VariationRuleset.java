package com.thanhdatpb.listingguard.variation.config;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.variation.Texts;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A parsed, validated variation ruleset (ADR-015): everything a rule needs, with the source of each claim. Immutable.
 * Names that are compared (themes, product types) are keyed by their folded form (see {@link Texts#fold}), and each value
 * keeps the spelling the file uses, for messages.
 *
 * @param packId       always {@value #PACK_ID}
 * @param contentHash  SHA-256 of the file text after line endings are normalized to LF and a BOM is dropped (ADR-002)
 * @param maxValueChars the longest text kept in a finding's {@code value} and in the client text a message quotes
 * @param themes       the known themes and their attributes, by folded name
 * @param rules        the rules in the order the file declares them, which is the order of the findings
 */
public record VariationRuleset(String packId, String version, LocalDate checkedAt, String contentHash, String disclaimer,
                               int maxValueChars, Map<String, Theme> themes, List<RuleSpec> rules,
                               ThemeAllowed themeAllowed, Apparel apparel, ChildrenDiffer childrenDiffer,
                               EtsyLimits etsyLimits) {

    public static final String PACK_ID = "variation-checks";
    public static final String AMAZON = "AMAZON";
    public static final String ETSY = "ETSY";

    public VariationRuleset {
        themes = Map.copyOf(themes);
        rules = List.copyOf(rules);
    }

    /** @throws IllegalStateException if the ruleset has no such rule (the loader guarantees every rule) */
    public RuleSpec rule(String id) {
        return rules.stream().filter(rule -> rule.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Ruleset " + version + " has no rule " + id));
    }

    /** Every marketplace at least one rule runs for. */
    public Set<String> marketplaces() {
        Set<String> all = new LinkedHashSet<>();
        rules.forEach(rule -> all.addAll(rule.marketplaces()));
        return all;
    }

    /** A known theme by name, ignoring case. */
    public Optional<Theme> theme(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(themes.get(Texts.fold(name)));
    }

    /** A theme: its name as the file spells it, the attributes a child must fill, and its source. */
    public record Theme(String name, List<String> attributes, Source source) {

        public Theme {
            attributes = List.copyOf(attributes);
        }
    }

    /** One finding kind of a rule: its severity and its Vietnamese message and suggestion with {@code {placeholders}}. */
    public record Kind(String name, Severity severity, String message, String suggestion) {
    }

    /** What every rule has: where it runs, where it comes from, and its kinds. */
    public record RuleSpec(String id, Set<String> marketplaces, Source source, Map<String, Kind> kinds) {

        public RuleSpec {
            marketplaces = Set.copyOf(marketplaces);
            kinds = Map.copyOf(kinds);
        }

        public Kind kind(String name) {
            Kind kind = kinds.get(name);
            if (kind == null) {
                throw new IllegalStateException("Rule " + id + " has no kind " + name);
            }
            return kind;
        }
    }

    /** {@code variation_theme_allowed}: the themes each known product type allows, by folded product type. */
    public record ThemeAllowed(Map<String, ProductTypeThemes> productTypes) {

        public ThemeAllowed {
            productTypes = Map.copyOf(productTypes);
        }

        public Optional<ProductTypeThemes> find(String productType) {
            return productType == null ? Optional.empty() : Optional.ofNullable(productTypes.get(Texts.fold(productType)));
        }
    }

    public record ProductTypeThemes(String productType, List<String> themes, Source source) {

        public ProductTypeThemes {
            themes = List.copyOf(themes);
        }
    }

    /**
     * {@code apparel_size_attributes}: the product types it applies to (folded), the attributes every child must fill,
     * the closed value lists, how a size range is read, and the order of alpha sizes.
     */
    public record Apparel(Set<String> productTypes, Source productTypesSource, List<String> requiredAttributes,
                          Map<String, List<String>> valueLists, Range range, AlphaScale alphaScale) {

        public Apparel {
            productTypes = Set.copyOf(productTypes);
            requiredAttributes = List.copyOf(requiredAttributes);
            valueLists = Map.copyOf(valueLists);
        }

        public boolean covers(String productType) {
            return productType != null && productTypes.contains(Texts.fold(productType));
        }
    }

    /**
     * How a range of sizes is read: which attributes hold the class, the size and the end of the range, and which size
     * classes are ranges (folded class name to how its values are compared).
     */
    public record Range(String classAttribute, String sizeAttribute, String sizeToAttribute,
                        Map<String, RangeKind> classes) {

        public Range {
            classes = Map.copyOf(classes);
        }
    }

    public enum RangeKind {
        NUMERIC,
        ALPHA,
        /** A range that is not compared (no sourced way to order its values). */
        NONE
    }

    /** Alpha size labels from the smallest to the largest; labels in one inner list share a rank. All folded. */
    public record AlphaScale(List<List<String>> order, Source source) {

        public AlphaScale {
            order = order.stream().map(List::copyOf).toList();
        }

        /** The rank of a label (0 is the smallest), or -1 when the scale does not know it. */
        public int rank(String label) {
            String folded = Texts.fold(label);
            for (int rank = 0; rank < order.size(); rank++) {
                if (order.get(rank).contains(folded)) {
                    return rank;
                }
            }
            return -1;
        }
    }

    /** {@code children_differ_outside_theme}: the words (folded tokens) that are ignored when titles are compared. */
    public record ChildrenDiffer(Set<String> ignoreWords, Source ignoreWordsSource) {

        public ChildrenDiffer {
            ignoreWords = Set.copyOf(ignoreWords);
        }
    }

    /**
     * {@code etsy_variation_limits}: the most variation types, the properties that cannot be a variation (as the file
     * spells them), and how many variations can have a photo, each with its own source.
     */
    public record EtsyLimits(int maxVariationTypes, Source maxVariationTypesSource, List<String> unsupportedProperties,
                             Source unsupportedPropertiesSource, int photoVariationLimit, Source photoVariationLimitSource) {

        public EtsyLimits {
            unsupportedProperties = List.copyOf(unsupportedProperties);
        }
    }
}
