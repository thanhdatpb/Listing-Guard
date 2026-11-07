package com.thanhdatpb.listingguard.personalization;

/**
 * The versions of the parts of this pack that are code, not ruleset (ADR-016, ADR-010). They take part in the analysis
 * fingerprint, so changing what one of them describes must change its constant.
 *
 * @param input the canonical input format and hash ({@link CanonicalInput})
 * @param rules how characters are classified and counted, how whitespace is read ({@link Texts}), how findings are
 *              ordered, and how the values are handed to the trademark scanner and its findings read back
 */
public record AlgorithmVersions(String input, String rules) {

    public static final String INPUT = "personalization-input-1";
    public static final String RULES = "personalization-rules-1";

    public static final AlgorithmVersions CURRENT = new AlgorithmVersions(INPUT, RULES);
}
