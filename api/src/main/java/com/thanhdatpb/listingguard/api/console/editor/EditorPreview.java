package com.thanhdatpb.listingguard.api.console.editor;

import java.util.List;

/**
 * The text of the "Simulation" panel: the listing as the seller typed it, laid out as a search result and as a product page.
 * It is a drawing of text, not of any marketplace's real page. Nothing about how a real site truncates a title is known here, so
 * no such number exists in this class: the search result is cut by the style sheet (a fixed number of lines with an ellipsis),
 * and the product page shows the whole title with the part beyond the ruleset's title limit set apart, so the seller sees where
 * the rule would object.
 *
 * @param titleWithin the title up to the limit (the whole title when there is no limit or it is within it)
 * @param titleBeyond the rest of the title, past the limit; empty when the title fits
 */
public record EditorPreview(String titleWithin, String titleBeyond, String price, String brand, List<String> bullets,
                            String description) {

    public String title() {
        return titleWithin + titleBeyond;
    }

    public boolean hasTitle() {
        return !title().isEmpty();
    }

    public boolean overTheLimit() {
        return !titleBeyond.isEmpty();
    }

    /**
     * @param titleMax the title limit of the ruleset, in code points like the rule's own count; null for no limit
     */
    public static EditorPreview of(EditorForm form, Integer titleMax) {
        String title = form.title().trim();
        String within = title;
        String beyond = "";
        if (titleMax != null && title.codePointCount(0, title.length()) > titleMax) {
            int cut = title.offsetByCodePoints(0, titleMax);
            within = title.substring(0, cut);
            beyond = title.substring(cut);
        }
        List<String> bullets = EditorListingMapper.lines(form.bulletPoints()).stream().map(String::trim)
                .filter(line -> !line.isEmpty()).toList();
        return new EditorPreview(within, beyond, form.price().trim(), form.brand().trim(), bullets,
                form.description().trim());
    }
}
