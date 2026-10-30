package com.thanhdatpb.listingguard.rag.analysis;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * The comparison form used on both sides of a citation check (ADR-011 item 6): Unicode NFKC, curly quotes and
 * apostrophes to straight ones, en and em dashes to {@code -}, every whitespace run (NBSP and newlines
 * included) to one space, then trim. Case is kept: a quote must be verbatim. Nothing else is repaired.
 */
final class TextNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+", Pattern.UNICODE_CHARACTER_CLASS);

    private TextNormalizer() {
    }

    static String normalize(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        s = s.replace('‘', '\'').replace('’', '\'').replace('‚', '\'').replace('‛', '\'')
                .replace('“', '"').replace('”', '"').replace('„', '"').replace('‟', '"')
                .replace('–', '-').replace('—', '-');
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }
}
