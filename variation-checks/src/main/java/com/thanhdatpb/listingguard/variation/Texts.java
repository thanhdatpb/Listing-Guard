package com.thanhdatpb.listingguard.variation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How text is compared and cut in this pack (ADR-015). Comparison never changes what is stored or shown: it only decides
 * whether two values are the same. Changing any of it changes findings, so it is covered by
 * {@link AlgorithmVersions#RULES}.
 */
public final class Texts {

    private Texts() {
    }

    /** NFC, then strip. Null is empty. This is the form that is hashed. */
    public static String canonical(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    }

    /** The form two values are compared in: canonical, then lower case. */
    public static String fold(String value) {
        return canonical(value).toLowerCase(Locale.ROOT);
    }

    /** Lower-case tokens: the runs of letters and digits of the text, in order. */
    public static List<String> tokens(String value) {
        List<String> tokens = new ArrayList<>();
        String folded = fold(value);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < folded.length(); ) {
            int cp = folded.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                current.appendCodePoint(cp);
            } else if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** At most {@code maxChars} code points of the text; the whole text when it is shorter. Null stays null. */
    public static String cut(String value, int maxChars) {
        if (value == null || value.codePointCount(0, value.length()) <= maxChars) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxChars));
    }
}
