package com.thanhdatpb.listingguard.rag.policy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Builds sections from a stream of headings and text units. A heading of level L closes every open
 * heading of level L or deeper, so the open headings always form the path of the current section.
 */
final class SectionAssembler {

    private record Heading(int level, String text, String id) {
    }

    private final List<Heading> open = new ArrayList<>();
    private final List<ParsedPolicy.Section> sections = new ArrayList<>();

    private List<String> currentPath;
    private String currentAnchor;
    private List<String> currentUnits;

    /** Starts a new section under a heading. Blank headings are ignored. */
    void heading(int level, String text, String id) {
        if (text == null || text.isBlank()) {
            return;
        }
        flush();
        while (!open.isEmpty() && open.get(open.size() - 1).level() >= level) {
            open.remove(open.size() - 1);
        }
        open.add(new Heading(level, text, id == null || id.isBlank() ? null : id));
    }

    /** Adds a paragraph or list item to the current section. Blank text is ignored. */
    void unit(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (currentUnits == null) {
            currentPath = open.stream().map(Heading::text).toList();
            currentAnchor = nearestAnchor();
            currentUnits = new ArrayList<>();
        }
        currentUnits.add(text);
    }

    /** Level of the deepest open heading at {@code minLevel} or deeper whose text passes the test, or -1. */
    int deepestOpenLevel(int minLevel, Predicate<String> textTest) {
        for (int i = open.size() - 1; i >= 0; i--) {
            Heading heading = open.get(i);
            if (heading.level() >= minLevel && textTest.test(heading.text())) {
                return heading.level();
            }
        }
        return -1;
    }

    /** Text of the deepest open heading with exactly this level, or null. */
    String openHeadingAt(int level) {
        for (int i = open.size() - 1; i >= 0; i--) {
            if (open.get(i).level() == level) {
                return open.get(i).text();
            }
        }
        return null;
    }

    ParsedPolicy build(LocalDate lastUpdated) {
        flush();
        return new ParsedPolicy(sections, lastUpdated);
    }

    private void flush() {
        if (currentUnits != null && !currentUnits.isEmpty()) {
            sections.add(new ParsedPolicy.Section(currentPath, currentAnchor, currentUnits));
        }
        currentUnits = null;
        currentPath = null;
        currentAnchor = null;
    }

    private String nearestAnchor() {
        for (int i = open.size() - 1; i >= 0; i--) {
            if (open.get(i).id() != null) {
                return open.get(i).id();
            }
        }
        return null;
    }
}
