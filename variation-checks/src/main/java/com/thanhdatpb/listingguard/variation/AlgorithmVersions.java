package com.thanhdatpb.listingguard.variation;

/**
 * The versions of the parts of this pack that are code, not ruleset (ADR-015, ADR-010). They take part in the analysis
 * fingerprint, so changing what one of them describes must change its constant.
 *
 * @param input the canonical input format and hash ({@link CanonicalInput})
 * @param rules how values, titles and SKUs are compared and how the rules combine them ({@link Texts} and the rules)
 */
public record AlgorithmVersions(String input, String rules) {

    public static final String INPUT = "variation-input-1";
    public static final String RULES = "variation-rules-1";

    public static final AlgorithmVersions CURRENT = new AlgorithmVersions(INPUT, RULES);
}
