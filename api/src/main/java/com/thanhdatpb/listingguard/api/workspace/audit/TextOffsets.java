package com.thanhdatpb.listingguard.api.workspace.audit;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Maps a span from the text a check worked on back to the text the workspace stores (ADR-061). A span is a pair of UTF-16 offsets, the end
 * exclusive; a result is {@link Optional#empty()} whenever the mapping cannot be shown to be right, and the page then marks the field only.
 *
 * <p>Two coordinate systems exist. A <b>rule</b> reads the normalized listing: the stored text with {@link String#trim()} applied
 * ({@code JsonFields.text}), so a rule span differs from the stored one by the leading characters the trim removed. A <b>trademark</b>
 * finding is in the canonical text of ADR-009: NFC, then {@link String#strip()}, of that trimmed text. NFC can change the length of a
 * text (a letter with a combining accent becomes one character), so that span is mapped cluster by cluster: the text is cut into grapheme
 * clusters, each cluster is composed on its own, and the result must equal the composition of the whole text, or nothing is mapped.
 * A span that starts or ends inside a cluster is widened to the whole cluster, never narrowed, so the marked text always contains the match.
 */
public final class TextOffsets {

    private TextOffsets() {
    }

    /** A span in the stored text; {@code end} is exclusive. */
    public record Span(int start, int end) {
    }

    /** The span of a rule finding (offsets in the trimmed text) in the stored text. */
    public static Optional<Span> fromRule(String stored, int start, int end) {
        if (stored == null || start < 0 || end <= start) {
            return Optional.empty();
        }
        int lead = trimOffset(stored);
        String trimmed = stored.trim();
        if (end > trimmed.length()) {
            return Optional.empty();
        }
        return Optional.of(new Span(lead + start, lead + end));
    }

    /** The span of a trademark finding (offsets in the NFC, stripped text of the trimmed text) in the stored text. */
    public static Optional<Span> fromCanonical(String stored, int start, int end) {
        if (stored == null || start < 0 || end <= start) {
            return Optional.empty();
        }
        int lead = trimOffset(stored);
        String trimmed = stored.trim();
        String composed = Normalizer.normalize(trimmed, Normalizer.Form.NFC);
        int strip = composed.length() - composed.stripLeading().length();
        int composedStart = strip + start;
        int composedEnd = strip + end;
        if (composedEnd > composed.stripTrailing().length()) {
            return Optional.empty();
        }
        List<int[]> clusters = clusters(trimmed);
        // the clusters, composed one by one, must give the composition of the whole; otherwise a composition crossed a cluster boundary
        StringBuilder rebuilt = new StringBuilder();
        for (int[] cluster : clusters) {
            rebuilt.append(Normalizer.normalize(trimmed.substring(cluster[0], cluster[1]), Normalizer.Form.NFC));
        }
        if (!rebuilt.toString().equals(composed)) {
            return Optional.empty();
        }
        int at = 0;
        int from = -1;
        int to = -1;
        for (int[] cluster : clusters) {
            int length = Normalizer.normalize(trimmed.substring(cluster[0], cluster[1]), Normalizer.Form.NFC).length();
            int clusterStart = at;
            int clusterEnd = at + length;
            if (from < 0 && composedStart < clusterEnd) {
                from = cluster[0];
            }
            if (composedEnd > clusterStart) {
                to = cluster[1];
            }
            at = clusterEnd;
        }
        if (from < 0 || to <= from) {
            return Optional.empty();
        }
        return Optional.of(new Span(lead + from, lead + to));
    }

    /** How many leading characters {@link String#trim()} removes. */
    static int trimOffset(String text) {
        int i = 0;
        while (i < text.length() && text.charAt(i) <= ' ') {
            i++;
        }
        return i;
    }

    private static List<int[]> clusters(String text) {
        List<int[]> clusters = new ArrayList<>();
        BreakIterator breaks = BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        breaks.setText(text);
        int start = breaks.first();
        for (int end = breaks.next(); end != BreakIterator.DONE; start = end, end = breaks.next()) {
            clusters.add(new int[] {start, end});
        }
        return clusters;
    }
}
