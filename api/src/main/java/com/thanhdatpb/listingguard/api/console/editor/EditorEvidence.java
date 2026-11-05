package com.thanhdatpb.listingguard.api.console.editor;

import com.thanhdatpb.listingguard.core.listing.Listing;
import java.text.Normalizer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the highlighted evidence of a finding. A rule finding is about a value, which is shown whole and marked. A trademark
 * finding is about a span of a field: {@code start} inclusive and {@code end} exclusive, in UTF-16 code units of the field's
 * text after trimming and Unicode NFC (ADR-009), so the span is cut out of that same text with a little context on each side.
 * When the span does not fit the text (it never should) the finding's own snippet is shown instead of a wrong cut.
 */
final class EditorEvidence {

    /** How much text is shown on each side of a span. A display choice, not a marketplace limit. */
    static final int CONTEXT = 40;

    private static final Pattern INDEXED = Pattern.compile("(\\w+)\\[(\\d+)]");

    private EditorEvidence() {
    }

    /** The whole value, marked; null when the finding has no single value (a missing field). */
    static EditorResult.Evidence ofValue(String value) {
        return value == null || value.isEmpty() ? null : new EditorResult.Evidence("", value, "", false, false);
    }

    static EditorResult.Evidence ofSpan(String text, int start, int end, String snippet) {
        if (text == null || start < 0 || end > text.length() || start >= end) {
            return snippet == null || snippet.isEmpty() ? null : new EditorResult.Evidence("", snippet, "", false, false);
        }
        int from = Math.max(0, start - CONTEXT);
        if (from > 0 && Character.isLowSurrogate(text.charAt(from))) {
            from++;
        }
        int to = Math.min(text.length(), end + CONTEXT);
        if (to < text.length() && Character.isLowSurrogate(text.charAt(to))) {
            to++;
        }
        return new EditorResult.Evidence(text.substring(from, start), text.substring(start, end), text.substring(end, to),
                from > 0, to < text.length());
    }

    /**
     * The text of a finding's field as the trademark scanner saw it: the normalized listing's value, in Unicode NFC. Fields
     * are {@code title}, {@code description}, {@code brand}, {@code search_terms}, and {@code tags[i]} / {@code bullet_points[i]}
     * counting the cleaned list. Null when the listing has no such value.
     */
    static String fieldText(Listing listing, String field) {
        if (field == null) {
            return null;
        }
        Matcher indexed = INDEXED.matcher(field);
        String text;
        if (indexed.matches()) {
            List<String> list = switch (indexed.group(1)) {
                case "tags" -> listing.tags();
                case "bullet_points" -> listing.bulletPoints();
                default -> List.of();
            };
            int index = Integer.parseInt(indexed.group(2));
            text = index < list.size() ? list.get(index) : null;
        } else {
            text = switch (field) {
                case "title" -> listing.title();
                case "description" -> listing.description();
                case "brand" -> listing.brand();
                case "search_terms" -> listing.searchTerms();
                default -> null;
            };
        }
        return text == null ? null : Normalizer.normalize(text, Normalizer.Form.NFC);
    }
}
