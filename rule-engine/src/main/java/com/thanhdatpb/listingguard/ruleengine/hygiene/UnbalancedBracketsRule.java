package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A bracket that is opened and never closed, or closed and never opened ({@code Mug (11oz}, {@code Set of 2)}), for the pairs of {@code pairs}.
 * A smiley ({@code :)}) and a list marker at the start of a line or after a space ({@code 1)}) while nothing is open are not brackets of the
 * text and are skipped. The first problem of a field is reported, with the bracket as its value. No fix: where the bracket belongs is not certain.
 */
public final class UnbalancedBracketsRule extends TextRule {

    public static final String ID = "unbalanced_brackets";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        pairs(settings);
    }

    private static List<String> pairs(RuleSettings settings) {
        List<String> pairs = settings.stringListParam("pairs");
        if (pairs.isEmpty() || pairs.stream().anyMatch(p -> p.codePointCount(0, p.length()) != 2)) {
            throw new RulesetException("Rule '" + ID + "': 'pairs' must be a list of two-character strings such as \"()\"");
        }
        return pairs;
    }

    @Override
    List<Hit> scan(String field, String text, RuleSettings settings) {
        List<String> pairs = pairs(settings);
        Deque<int[]> open = new ArrayDeque<>();
        int firstProblem = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int kind = -1;
            boolean opener = false;
            for (int p = 0; p < pairs.size(); p++) {
                if (pairs.get(p).charAt(0) == c) {
                    kind = p;
                    opener = true;
                } else if (pairs.get(p).charAt(1) == c) {
                    kind = p;
                }
            }
            if (kind < 0 || smiley(text, i)) {
                continue;
            }
            if (opener) {
                open.push(new int[] {kind, i});
            } else if (!open.isEmpty() && open.peek()[0] == kind) {
                open.pop();
            } else if (open.isEmpty() && listMarker(text, i)) {
                continue;
            } else if (firstProblem < 0) {
                firstProblem = i;
            }
        }
        int leftover = open.stream().mapToInt(o -> o[1]).min().orElse(-1);
        int at = firstProblem < 0 ? leftover : leftover < 0 ? firstProblem : Math.min(firstProblem, leftover);
        if (at < 0) {
            return List.of();
        }
        String bracket = text.substring(at, at + 1);
        boolean opened = pairs.stream().anyMatch(p -> p.charAt(0) == text.charAt(at));
        return List.of(new Hit(bracket, at, at + 1, "Dấu ngoặc " + quote(bracket) + " trong " + field
                + (opened ? " không có dấu đóng." : " không có dấu mở."),
                "Thêm dấu " + (opened ? "đóng" : "mở") + " còn thiếu hoặc bỏ dấu ngoặc thừa."));
    }

    /** {@code :)} {@code :-(} {@code ;)} {@code =)} and the like: a bracket right after a mouth character. */
    private static boolean smiley(String text, int i) {
        char before = i > 0 ? text.charAt(i - 1) : ' ';
        char twoBefore = i > 1 ? text.charAt(i - 2) : ' ';
        return ":;=".indexOf(before) >= 0 || (before == '-' || before == '\'') && ":;=".indexOf(twoBefore) >= 0;
    }

    /** A closing bracket that ends one or two letters or digits which start a line or follow a blank: {@code 1)}, {@code a)}. */
    private static boolean listMarker(String text, int i) {
        int j = i;
        while (j > 0 && Character.isLetterOrDigit(text.charAt(j - 1))) {
            j--;
        }
        int length = i - j;
        return length >= 1 && length <= 2 && (j == 0 || Character.isWhitespace(text.charAt(j - 1)));
    }
}
