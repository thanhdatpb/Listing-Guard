package com.thanhdatpb.listingguard.ruleengine.amazon;

import java.nio.charset.StandardCharsets;

/** Small text helpers shared by the Amazon rules. */
final class Texts {

    private static final int PREVIEW_LENGTH = 25;

    private Texts() {
    }

    /** Length in characters (code points), so emoji and other supplementary characters count once. */
    static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** Length in UTF-8 bytes, the unit Amazon uses for search terms. */
    static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Quotes a value for a message, cutting long values with "...". */
    static String quote(String text) {
        if (length(text) <= PREVIEW_LENGTH) {
            return "'" + text + "'";
        }
        int end = text.offsetByCodePoints(0, PREVIEW_LENGTH);
        return "'" + text.substring(0, end) + "...'";
    }
}
