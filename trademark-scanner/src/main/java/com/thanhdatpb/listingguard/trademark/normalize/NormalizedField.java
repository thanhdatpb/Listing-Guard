package com.thanhdatpb.listingguard.trademark.normalize;

import java.util.List;

/**
 * One scanned field: its canonical text and its normalized tokens, each with a span in that text.
 *
 * @param name   {@code title}, {@code tags[0]}, {@code description}, ...
 * @param text   the canonical text (NFC, stripped)
 * @param tokens the normalized tokens in order
 */
public record NormalizedField(String name, String text, List<Token> tokens) {

    public NormalizedField {
        tokens = List.copyOf(tokens);
    }

    /** The normalized texts of tokens {@code from} to {@code to} (inclusive) joined by one space. */
    public String join(int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (i > from) {
                out.append(' ');
            }
            out.append(tokens.get(i).text());
        }
        return out.toString();
    }
}
