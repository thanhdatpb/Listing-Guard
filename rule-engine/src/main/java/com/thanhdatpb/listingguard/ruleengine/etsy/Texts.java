package com.thanhdatpb.listingguard.ruleengine.etsy;

/** Small text helpers shared by the Etsy rules. */
final class Texts {

    private static final int PREVIEW_LENGTH = 25;

    private Texts() {
    }

    /** Length in characters (code points), so emoji and other supplementary characters count once. */
    static int length(String text) {
        return text.codePointCount(0, text.length());
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
