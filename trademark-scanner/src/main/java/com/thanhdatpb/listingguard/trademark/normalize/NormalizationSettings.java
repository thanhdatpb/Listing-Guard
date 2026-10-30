package com.thanhdatpb.listingguard.trademark.normalize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

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

    public static final String HASH_FORMAT = "listing-guard/normalization-settings/v1";

    public NormalizationSettings {
        foldMap = Map.copyOf(foldMap);
        homoglyphs = Map.copyOf(homoglyphs);
        leetMap = Map.copyOf(leetMap);
        deleteChars = Set.copyOf(deleteChars);
    }

    /**
     * SHA-256 of the settings in a canonical text: every table sorted by code point, keys and values as hexadecimal
     * code points. It is taken from the parsed settings, not from the YAML bytes, so a comment, the order of keys or
     * the quoting style does not change it, while any mapping, the leet distance or a deleted character does. A catalog
     * is normalized with these settings and {@link TextNormalizer#VERSION}, and nothing else of the ruleset.
     */
    public String canonicalHash() {
        StringBuilder text = new StringBuilder(HASH_FORMAT).append('\n');
        appendTable(text, "fold_map", foldMap);
        appendTable(text, "homoglyphs", homoglyphs);
        text.append("leet_min_letters_around=").append(leetMinLettersAround).append('\n');
        appendTable(text, "leet_map", leetMap);
        text.append("delete_chars=");
        new TreeSet<>(deleteChars).forEach(codePoint -> text.append(Integer.toHexString(codePoint)).append(','));
        text.append('\n');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void appendTable(StringBuilder text, String name, Map<Integer, String> table) {
        text.append(name).append('\n');
        new TreeMap<>(table).forEach((codePoint, value) -> text.append(Integer.toHexString(codePoint)).append('=')
                .append(value.codePoints().mapToObj(Integer::toHexString).collect(Collectors.joining(".")))
                .append('\n'));
    }
}
