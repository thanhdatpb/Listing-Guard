package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.core.listing.Listing;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The listing as it was checked, read only, for the left column of a result (ADR-041): the title with the words a finding points at
 * marked, the tags (or the bullet points) with the ones a finding points at flagged, and the start of the description. It is built for
 * the response and never kept; it holds what the seller typed, so the page prints it with {@code th:text} only.
 *
 * <p>A {@link Mark} says where a finding points: a span of a field (a trademark finding, in UTF-16 units of the field's text after
 * trimming and NFC, ADR-009) or a value to find in the field (a rule finding). A mark never changes a finding: it is only where the
 * page puts the highlight. A span that does not fit the text is dropped, never guessed.
 *
 * @param title       the title in segments, marks included
 * @param titleChars  the title's length in code points
 * @param titleMax    the rules' title limit, or null when the page does not know it
 * @param itemsKind   {@code tags} (Etsy) or {@code bullets} (Amazon)
 * @param items       the tags or bullet points, in order
 * @param itemsMax    the rules' limit on how many there may be, or null
 * @param description the start of the description (cut), or empty
 */
public record ListingView(List<Segment> title, int titleChars, Integer titleMax, String itemsKind, List<Item> items, Integer itemsMax,
                          String description) {

    /** How much of the description the page shows. A display choice, not a marketplace limit. */
    static final int DESCRIPTION_CHARS = 280;

    /** {@code tone} is empty for plain text, else {@code error}, {@code trademark} or {@code warn}. */
    public record Segment(String text, String tone) {
    }

    /** One tag or bullet point; {@code flag} is empty, {@code flag} (trademark or warning) or {@code error}. */
    public record Item(List<Segment> segments, String flag) {

        public String text() {
            StringBuilder text = new StringBuilder();
            segments.forEach(segment -> text.append(segment.text()));
            return text.toString();
        }
    }

    /**
     * Where a finding points. {@code field} is {@code title}, {@code tags[i]} or {@code bullet_points[i]}; with {@code start >= 0} the
     * span is {@code [start, end)}, else {@code value} is looked for in the field. {@code tone} is {@code error}, {@code trademark}
     * or {@code warn}.
     */
    public record Mark(String field, int start, int end, String value, String tone) {

        public static Mark span(String field, int start, int end, String tone) {
            return new Mark(field, start, end, null, tone);
        }

        public static Mark value(String field, String value, String tone) {
            return new Mark(field, -1, -1, value, tone);
        }
    }

    /** The same view with the limits of the rules, which the page knows and the check does not. */
    public ListingView withLimits(Integer titleMax, Integer itemsMax) {
        return new ListingView(title, titleChars, titleMax, itemsKind, items, itemsMax, description);
    }

    /** True when there is anything to show besides an empty title. */
    public boolean hasContent() {
        return titleChars > 0 || !items.isEmpty() || !description.isEmpty();
    }

    public static ListingView of(Listing listing, boolean etsy, List<Mark> marks, Integer titleMax, Integer itemsMax) {
        String title = nfc(listing.title());
        List<String> list = etsy ? listing.tags() : listing.bulletPoints();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String text = nfc(list.get(i));
            String field = (etsy ? "tags[" : "bullet_points[") + i + "]";
            List<Mark> mine = marks.stream().filter(mark -> field.equals(mark.field())).toList();
            // a tag is a chip, flagged as a whole; a bullet point is text, and the words a finding points at are marked
            items.add(etsy ? new Item(List.of(new Segment(text, "")), flag(mine)) : new Item(segments(text, mine), flag(mine)));
        }
        String description = nfc(listing.description());
        if (description.codePointCount(0, description.length()) > DESCRIPTION_CHARS) {
            description = description.substring(0, description.offsetByCodePoints(0, DESCRIPTION_CHARS)).stripTrailing() + "...";
        }
        return new ListingView(segments(title, marks.stream().filter(mark -> "title".equals(mark.field())).toList()),
                title.codePointCount(0, title.length()), titleMax, etsy ? "tags" : "bullets", List.copyOf(items), itemsMax,
                description);
    }

    /** The mark a rule finding makes: the value it is about, for an ERROR or a WARNING; null for anything else. */
    public static Mark ruleMark(String field, String value, String severity) {
        if (field == null || value == null || value.isBlank() || !("error".equals(severity) || "warning".equals(severity))) {
            return null;
        }
        return Mark.value(field, value, "error".equals(severity) ? "error" : "warn");
    }

    private static String flag(List<Mark> marks) {
        if (marks.isEmpty()) {
            return "";
        }
        return marks.stream().anyMatch(mark -> "error".equals(mark.tone())) ? "error" : "flag";
    }

    /** Cuts {@code text} at the marks that fit it; an error mark wins over another where two overlap. */
    private static List<Segment> segments(String text, List<Mark> marks) {
        List<int[]> spans = new ArrayList<>();
        List<String> tones = new ArrayList<>();
        for (Mark mark : marks) {
            int start = mark.start();
            int end = mark.end();
            if (start < 0) {
                String value = mark.value() == null ? "" : nfc(mark.value()).strip();
                int at = value.isEmpty() ? -1 : text.indexOf(value);
                if (at < 0 || value.length() >= text.strip().length()) {
                    // not found, or all of the field: there is nothing to point at inside it
                    continue;
                }
                start = at;
                end = at + value.length();
            }
            if (start < 0 || end > text.length() || start >= end) {
                continue;
            }
            spans.add(new int[] {start, end, "error".equals(mark.tone()) ? 0 : 1});
            tones.add(mark.tone());
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingInt(i -> spans.get(i)[2]).thenComparingInt(i -> spans.get(i)[0]));
        boolean[] taken = new boolean[text.length()];
        List<int[]> kept = new ArrayList<>();
        List<String> keptTones = new ArrayList<>();
        for (int i : order) {
            int[] span = spans.get(i);
            boolean free = true;
            for (int at = span[0]; at < span[1]; at++) {
                free &= !taken[at];
            }
            if (free) {
                for (int at = span[0]; at < span[1]; at++) {
                    taken[at] = true;
                }
                kept.add(span);
                keptTones.add(tones.get(i));
            }
        }
        List<Integer> byStart = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            byStart.add(i);
        }
        byStart.sort(Comparator.comparingInt(i -> kept.get(i)[0]));
        List<Segment> segments = new ArrayList<>();
        int at = 0;
        for (int i : byStart) {
            int[] span = kept.get(i);
            if (span[0] > at) {
                segments.add(new Segment(text.substring(at, span[0]), ""));
            }
            segments.add(new Segment(text.substring(span[0], span[1]), keptTones.get(i)));
            at = span[1];
        }
        if (at < text.length()) {
            segments.add(new Segment(text.substring(at), ""));
        }
        return List.copyOf(segments);
    }

    private static String nfc(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFC);
    }
}
