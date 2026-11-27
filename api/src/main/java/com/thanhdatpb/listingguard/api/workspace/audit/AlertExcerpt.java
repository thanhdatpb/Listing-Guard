package com.thanhdatpb.listingguard.api.workspace.audit;

/**
 * The short piece of a listing's text that an alert e-mail quotes around a match (ADR-061): a few characters on each side of the span, cut at a
 * space where there is one, with an ellipsis where text was left out. It never returns more than the match and {@code around} characters on each side,
 * so an e-mail cannot carry a listing. White space is collapsed to one space (a table row stays on one line).
 */
public final class AlertExcerpt {

    private AlertExcerpt() {
    }

    /** The excerpt, or null when the span does not fit the text (the position is not known). */
    public static String of(String text, Integer start, Integer end, int around) {
        if (text == null || start == null || end == null || start < 0 || end <= start || end > text.length()) {
            return null;
        }
        int from = start;
        int to = end;
        int budget = around;
        // a surrogate pair is never cut in half
        while (budget > 0 && from > 0) {
            from -= Character.isLowSurrogate(text.charAt(from - 1)) && from > 1 ? 2 : 1;
            budget--;
        }
        budget = around;
        while (budget > 0 && to < text.length()) {
            to += Character.isHighSurrogate(text.charAt(to)) && to + 1 < text.length() ? 2 : 1;
            budget--;
        }
        from = Math.max(0, from);
        to = Math.min(text.length(), to);
        if (from > 0) {
            int space = text.indexOf(' ', from);
            if (space >= 0 && space < start) {
                from = space + 1;
            }
        }
        if (to < text.length()) {
            int space = text.lastIndexOf(' ', to);
            if (space > end) {
                to = space;
            }
        }
        String piece = text.substring(from, to).replaceAll("\\s+", " ").strip();
        return (from > 0 ? "…" : "") + piece + (to < text.length() ? "…" : "");
    }
}
