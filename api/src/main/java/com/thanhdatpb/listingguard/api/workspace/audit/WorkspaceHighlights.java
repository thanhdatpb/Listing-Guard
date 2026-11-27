package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredFinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cuts the text of each field of a listing into the pieces the detail page prints, marking the ones a finding points at (ADR-061). The text
 * is the stored text and the spans are offsets in it, so every piece is a slice of what the seller wrote and nothing is added to it. A finding
 * whose span does not fit its field (past the end, empty, inside a surrogate pair), a finding that lost an overlap to a worse one, and a
 * finding with no position at all are not marked in the text: they flag the field and are in the findings table.
 */
public final class WorkspaceHighlights {

    private WorkspaceHighlights() {
    }

    /**
     * One piece of a field. {@code finding} is the index of the finding the piece is marked for, or -1 for plain text; {@code tone} is the
     * finding's level code ({@code error}, {@code high}, {@code warning} or {@code medium}), empty for plain text.
     */
    public record Segment(String text, int finding, String tone) {

        public boolean marked() {
            return finding >= 0;
        }
    }

    /**
     * A field with its pieces. {@code flagged} lists the findings that point at the field and are not marked in its text, worst first;
     * {@code flagTone} is the tone of the worst finding that points at the field at all, empty when none does.
     */
    public record Field(String path, List<Segment> segments, List<Integer> flagged, String flagTone) {
    }

    /** The fields of {@code texts}, in order, each with the findings that point at it. {@code findings} is the order of the findings table. */
    public static List<Field> build(Map<String, String> texts, List<StoredFinding> findings) {
        List<Field> fields = new ArrayList<>();
        texts.forEach((path, text) -> fields.add(field(path, text, findings)));
        return fields;
    }

    /** The findings that point at no field the page prints (a count, a price, a missing field): they are in the table only. */
    public static List<Integer> elsewhere(Map<String, String> texts, List<StoredFinding> findings) {
        List<Integer> others = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            if (findings.get(i).field() == null || !texts.containsKey(findings.get(i).field())) {
                others.add(i);
            }
        }
        return others;
    }

    private static Field field(String path, String text, List<StoredFinding> findings) {
        List<Integer> mine = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            if (path.equals(findings.get(i).field())) {
                mine.add(i);
            }
        }
        // the worst first, a new risk before a known one, then the one that starts first: a worse finding wins an overlap
        List<Integer> order = new ArrayList<>(mine);
        order.sort(Comparator.<Integer>comparingInt(i -> -findings.get(i).level().weight()).thenComparing(i -> !findings.get(i).isNew())
                .thenComparingInt(i -> findings.get(i).spanStart() == null ? Integer.MAX_VALUE : findings.get(i).spanStart()));
        boolean[] taken = new boolean[text.length()];
        List<Integer> placed = new ArrayList<>();
        List<Integer> flagged = new ArrayList<>();
        for (int i : order) {
            StoredFinding f = findings.get(i);
            if (!f.hasSpan() || !fits(text, f.spanStart(), f.spanEnd()) || overlaps(taken, f.spanStart(), f.spanEnd())) {
                flagged.add(i);
                continue;
            }
            for (int at = f.spanStart(); at < f.spanEnd(); at++) {
                taken[at] = true;
            }
            placed.add(i);
        }
        placed.sort(Comparator.comparingInt(i -> findings.get(i).spanStart()));
        List<Segment> segments = new ArrayList<>();
        int at = 0;
        for (int i : placed) {
            StoredFinding f = findings.get(i);
            if (f.spanStart() > at) {
                segments.add(new Segment(text.substring(at, f.spanStart()), -1, ""));
            }
            segments.add(new Segment(text.substring(f.spanStart(), f.spanEnd()), i, f.level().code()));
            at = f.spanEnd();
        }
        if (at < text.length() || segments.isEmpty()) {
            segments.add(new Segment(text.substring(at), -1, ""));
        }
        String flagTone = order.isEmpty() ? "" : findings.get(order.get(0)).level().code();
        return new Field(path, List.copyOf(segments), List.copyOf(flagged), flagged.isEmpty() ? "" : flagTone);
    }

    private static boolean fits(String text, int start, int end) {
        if (start < 0 || end > text.length() || start >= end) {
            return false;
        }
        // a pair of surrogates is one character: a span never starts or ends between its two halves
        return !(start > 0 && Character.isLowSurrogate(text.charAt(start)) && Character.isHighSurrogate(text.charAt(start - 1)))
                && !(end < text.length() && Character.isLowSurrogate(text.charAt(end)) && Character.isHighSurrogate(text.charAt(end - 1)));
    }

    private static boolean overlaps(boolean[] taken, int start, int end) {
        for (int at = start; at < end; at++) {
            if (taken[at]) {
                return true;
            }
        }
        return false;
    }

    /** The tone's class suffix: {@code severe} for an error or a high risk, {@code caution} for a warning or a medium one. */
    public static String severity(String tone) {
        return "error".equals(tone) || "high".equals(tone) ? "severe" : "caution";
    }

    /** The findings by field, for a page that lists them under their field. */
    public static Map<String, List<Integer>> byField(List<StoredFinding> findings) {
        Map<String, List<Integer>> map = new LinkedHashMap<>();
        for (int i = 0; i < findings.size(); i++) {
            String field = findings.get(i).field();
            if (field != null) {
                map.computeIfAbsent(field, f -> new ArrayList<>()).add(i);
            }
        }
        return map;
    }
}
