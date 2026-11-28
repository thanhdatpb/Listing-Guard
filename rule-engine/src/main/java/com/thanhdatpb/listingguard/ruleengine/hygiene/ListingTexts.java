package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The texts of a listing that the content rules of ADR-039 read, by the same paths the rest of the engine and the workspace use
 * ({@code tags[2]} counts the cleaned list). Which fields a rule reads is a list in the ruleset ({@code fields}); a name this class does
 * not know fails at startup. A missing or blank text is not here.
 */
public final class ListingTexts {

    private static final Set<String> NAMES = Set.of("title", "description", "brand", "item_highlights", "search_terms", "tags",
            "bullet_points", "materials");

    private ListingTexts() {
    }

    /** @throws RulesetException when {@code fields} names something that is not a text of a listing */
    public static void validate(String ruleId, List<String> fields) {
        if (fields.isEmpty()) {
            throw new RulesetException("Rule '" + ruleId + "': 'fields' must name at least one field");
        }
        for (String field : fields) {
            if (!NAMES.contains(field)) {
                throw new RulesetException("Rule '" + ruleId + "': unknown field '" + field + "' (use " + new java.util.TreeSet<>(NAMES) + ")");
            }
        }
    }

    /** The texts of the listed fields, in the order of {@code fields}, a list field one text per entry. */
    public static List<FieldText> of(Listing listing, List<String> fields) {
        List<FieldText> texts = new ArrayList<>();
        for (String field : fields) {
            switch (field) {
                case "title" -> add(texts, field, listing.title());
                case "description" -> add(texts, field, listing.description());
                case "brand" -> add(texts, field, listing.brand());
                case "item_highlights" -> add(texts, field, listing.itemHighlights());
                case "search_terms" -> add(texts, field, listing.searchTerms());
                case "tags" -> addAll(texts, field, listing.tags());
                case "bullet_points" -> addAll(texts, field, listing.bulletPoints());
                case "materials" -> addAll(texts, field, listing.materials());
                default -> throw new IllegalArgumentException("Unknown field " + field);
            }
        }
        return texts;
    }

    private static void add(List<FieldText> texts, String field, String text) {
        if (text != null && !text.isBlank()) {
            texts.add(new FieldText(field, text));
        }
    }

    private static void addAll(List<FieldText> texts, String field, List<String> entries) {
        for (int i = 0; i < entries.size(); i++) {
            add(texts, field + "[" + i + "]", entries.get(i));
        }
    }
}
