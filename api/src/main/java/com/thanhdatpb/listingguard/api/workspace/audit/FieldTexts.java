package com.thanhdatpb.listingguard.api.workspace.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The text of a listing's standard fields by the path a finding uses ({@code title}, {@code tags[2]}, {@code bullet_points[0]}, ...), in the
 * order the page shows them (ADR-061). A list index counts the cleaned list (a blank entry is dropped, the rest keep their order), as
 * {@code JsonFields.strings} and the checks do. Only text that a person typed is here; a number, an object or an array in a field that holds
 * one text has no path, and neither has {@code search_terms} given as an array (it is joined for the check, so a position in it has no
 * place in a stored string).
 */
public final class FieldTexts {

    private FieldTexts() {
    }

    /** The plain text fields, then the lists, in display order. */
    public static Map<String, String> of(JsonNode fields) {
        Map<String, String> texts = new LinkedHashMap<>();
        if (fields == null || !fields.isObject()) {
            return texts;
        }
        put(texts, "title", fields.get("title"));
        put(texts, "brand", fields.get("brand"));
        putList(texts, "bullet_points", fields.get("bullet_points"));
        putList(texts, "tags", fields.get("tags"));
        put(texts, "item_highlights", fields.get("item_highlights"));
        put(texts, "description", fields.get("description"));
        put(texts, "search_terms", fields.get("search_terms"));
        return texts;
    }

    private static void put(Map<String, String> texts, String path, JsonNode node) {
        String text = text(node);
        if (text != null) {
            texts.put(path, text);
        }
    }

    private static void putList(Map<String, String> texts, String path, JsonNode node) {
        if (node == null || !node.isArray()) {
            return;
        }
        int index = 0;
        for (JsonNode item : node) {
            String text = text(item);
            if (text != null) {
                texts.put(path + "[" + index++ + "]", text);
            }
        }
    }

    /** What {@code JsonFields.text} reads: a plain value, trimmed, absent when blank. */
    static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value.trim().isEmpty() ? null : value;
    }
}
