package com.thanhdatpb.listingguard.rag.policy;

import java.time.LocalDate;
import java.util.List;

/**
 * A policy document reduced to its structure: sections in reading order. Navigation, page chrome and
 * the "Last updated" line are already removed.
 *
 * @param lastUpdated the page's own "Last updated on" date, or null when the page has none
 */
public record ParsedPolicy(List<Section> sections, LocalDate lastUpdated) {

    public ParsedPolicy {
        sections = List.copyOf(sections);
    }

    /**
     * @param headingPath headings from the outermost down to this section, without the document title; empty
     *                    for text before the first heading
     * @param anchor      {@code id} of the nearest heading that has one, or null
     * @param units       paragraphs and list items, each kept whole
     */
    public record Section(List<String> headingPath, String anchor, List<String> units) {

        public Section {
            headingPath = List.copyOf(headingPath);
            units = List.copyOf(units);
        }
    }
}
