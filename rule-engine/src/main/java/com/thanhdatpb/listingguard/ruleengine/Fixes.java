package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.core.finding.FixSuggestion;
import java.util.Optional;

/**
 * Builds the candidate fixes of ADR-014 for the rules that can be certain of a proposed value. A fix only shortens a
 * value at a word boundary or removes a duplicate; it never invents, translates or substitutes words. A fix returned
 * here is a candidate: {@link RuleEngine} verifies it against the whole ruleset before it leaves the engine.
 */
public final class Fixes {

    /** Separators that must not be left dangling at the end of a cut value. */
    private static final String TRAILING_SEPARATORS = ",;:|/-–—&+";
    private static final int PREVIEW_LENGTH = 25;

    private Fixes() {
    }

    /**
     * Cuts {@code text} to at most {@code max} characters at a word boundary.
     *
     * @param noun what the text is, for the description ("tiêu đề", "tag")
     * @return empty when there is no certain cut: the text already fits, or {@link #cutAtWordBoundary} finds none
     */
    public static Optional<FixSuggestion> shorten(String noun, String text, int max) {
        return cutAtWordBoundary(text, max).map(cut -> {
            String tail = text.substring(cut.length()).strip();
            int kept = cut.codePointCount(0, cut.length());
            return FixSuggestion.replace("Cắt " + noun + " tại ranh giới từ còn " + kept + " ký tự (tối đa " + max
                    + "); phần cuối bị bỏ: " + quote(tail) + ".", cut);
        });
    }

    /** Drops a repeated value; {@code firstPosition} is where the first occurrence is, counted from 1. */
    public static FixSuggestion removeRepeat(String noun, String value, int firstPosition) {
        return FixSuggestion.remove("Xoá " + noun + " " + quote(value) + " lặp lại; bản đầu tiên đã có ở vị trí "
                + firstPosition + ".");
    }

    /**
     * The longest prefix of {@code text} that has at most {@code max} code points, ends at a word boundary, has no
     * dangling separator and no unbalanced bracket. Empty when the text already fits or when there is no such prefix
     * (one long word, or text without spaces such as Chinese).
     */
    static Optional<String> cutAtWordBoundary(String text, int max) {
        int[] cps = text.codePoints().toArray();
        if (max <= 0 || cps.length <= max) {
            return Optional.empty();
        }
        int end = max;
        if (!isSpace(cps[max])) {
            end = -1;
            for (int i = max - 1; i >= 0; i--) {
                if (isSpace(cps[i])) {
                    end = i;
                    break;
                }
            }
            if (end < 0) {
                return Optional.empty();
            }
        }
        while (end > 0 && (isSpace(cps[end - 1]) || TRAILING_SEPARATORS.indexOf(cps[end - 1]) >= 0)) {
            end--;
        }
        if (end == 0 || !balanced(cps, end)) {
            return Optional.empty();
        }
        return Optional.of(new String(cps, 0, end));
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean balanced(int[] cps, int end) {
        int round = 0;
        int square = 0;
        for (int i = 0; i < end; i++) {
            switch (cps[i]) {
                case '(' -> round++;
                case ')' -> {
                    if (--round < 0) {
                        return false;
                    }
                }
                case '[' -> square++;
                case ']' -> {
                    if (--square < 0) {
                        return false;
                    }
                }
                default -> {
                }
            }
        }
        return round == 0 && square == 0;
    }

    private static String quote(String text) {
        if (text.codePointCount(0, text.length()) <= PREVIEW_LENGTH) {
            return "'" + text + "'";
        }
        return "'" + text.substring(0, text.offsetByCodePoints(0, PREVIEW_LENGTH)) + "...'";
    }
}
