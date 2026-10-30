package com.thanhdatpb.listingguard.trademark.normalize;

import java.util.Map;
import java.util.Set;

/**
 * The ruleset keys that drive {@link TextNormalizer}. All maps are keyed by one code point.
 *
 * @param foldMap              letters that Unicode decomposition leaves alone and that are mapped to ASCII, such as
 *                             the Vietnamese d with stroke
 * @param homoglyphs           look-alike letters mapped to the letter they imitate
 * @param leetMap              characters that may stand for a letter inside a word
 * @param leetMinLettersAround how many letters must precede and follow a leet character in its word for it to be
 *                             mapped
 * @param deleteChars          characters dropped without splitting a word (apostrophes, zero-width characters)
 */
public record NormalizationSettings(Map<Integer, String> foldMap, Map<Integer, String> homoglyphs,
                                    Map<Integer, String> leetMap, int leetMinLettersAround,
                                    Set<Integer> deleteChars) {

    public NormalizationSettings {
        foldMap = Map.copyOf(foldMap);
        homoglyphs = Map.copyOf(homoglyphs);
        leetMap = Map.copyOf(leetMap);
        deleteChars = Set.copyOf(deleteChars);
    }
}
