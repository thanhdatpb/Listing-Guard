package com.thanhdatpb.listingguard.personalization;

import java.text.Normalizer;
import java.util.Locale;

/**
 * How this pack reads text (ADR-016). Part of the analysis fingerprint through {@link AlgorithmVersions#RULES}: a change
 * to anything here that can change a finding needs a new version constant.
 */
public final class Texts {

    private Texts() {
    }

    /** Unicode NFC; null is empty. The buyer's text is not stripped, only normalized. */
    public static String nfc(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    /** NFC and {@code strip}; null is empty. For labels, codes and other text that is ours or the client's, not the buyer's. */
    public static String nfcStrip(String text) {
        return nfc(text).strip();
    }

    /**
     * The key a label is matched by: NFC, stripped, lower case. Null for no label (null or blank), so a value without a
     * label never matches a per-label limit.
     */
    public static String labelKey(String label) {
        String stripped = nfcStrip(label);
        return stripped.isEmpty() ? null : stripped.toLowerCase(Locale.ROOT);
    }

    /** The whitespace the rules look at: {@code Character.isWhitespace} or {@code Character.isSpaceChar} (no-break space and so on). */
    public static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /** The first {@code maxCodePoints} code points of the text, never splitting a surrogate pair. */
    public static String cut(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }

    /**
     * Makes text safe to keep as evidence: a control character (below U+0020 or U+007F, among them NUL, which PostgreSQL
     * cannot store) and a lone surrogate become U+FFFD. The exact code points are still named in the message.
     */
    public static String printable(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            boolean surrogate = codePoint >= 0xD800 && codePoint <= 0xDFFF;
            out.appendCodePoint(codePoint < 0x20 || codePoint == 0x7F || surrogate ? 0xFFFD : codePoint);
        });
        return out.toString();
    }
}
