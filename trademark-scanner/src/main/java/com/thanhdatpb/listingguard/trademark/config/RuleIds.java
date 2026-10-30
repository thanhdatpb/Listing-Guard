package com.thanhdatpb.listingguard.trademark.config;

import java.util.List;
import java.util.Map;

/**
 * The rule ids of the trademark pack and what each one needs. A ruleset declares every rule exactly once; the order
 * of the declaration is the order the rules run in and the tie-break between equal risks (ADR-009).
 */
public final class RuleIds {

    public static final String CATALOG_EXACT = "catalog_exact";
    public static final String CATALOG_PHRASE = "catalog_phrase";
    public static final String CATALOG_FUZZY = "catalog_fuzzy";
    public static final String GEO_SPORT_COMBINATION = "geo_sport_combination";
    public static final String COLOR_NAME_CONTAINS_MARK = "color_name_contains_mark";
    public static final String PURE_COLOR_DESCRIPTION = "pure_color_description";

    /** Every rule of the pack. */
    public static final List<String> ALL = List.of(CATALOG_EXACT, CATALOG_PHRASE, CATALOG_FUZZY,
            GEO_SPORT_COMBINATION, COLOR_NAME_CONTAINS_MARK, PURE_COLOR_DESCRIPTION);

    /** The catalog matching rules. */
    public static final List<String> CATALOG = List.of(CATALOG_EXACT, CATALOG_PHRASE, CATALOG_FUZZY);

    /**
     * Rules that must be declared before, and must not have failed for, the rule that needs them. The color rules
     * read what the catalog rules found; a color description cannot be called free of trademark references when the
     * catalog lookup failed.
     */
    public static final Map<String, List<String>> REQUIRES = Map.of(
            COLOR_NAME_CONTAINS_MARK, CATALOG,
            PURE_COLOR_DESCRIPTION, CATALOG);

    private RuleIds() {
    }
}
