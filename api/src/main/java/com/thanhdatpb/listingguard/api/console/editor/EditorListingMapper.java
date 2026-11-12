package com.thanhdatpb.listingguard.api.console.editor;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.input.StandardListingAdapter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the editor form into the standard listing JSON of {@code docs/input-schema.md}, the same object a client sends to
 * {@code POST /api/v1/checks}, so the check goes through the same normalizer as every other caller.
 *
 * <p>It only maps. A blank field is left out (absent, which the rules report); a value is passed on exactly as typed, because
 * the normalizer trims and the rules must see what the seller wrote. The two numbers are the exception, since the schema
 * wants a number: a price that is not a decimal number, or a quantity that is not a whole number, is refused with a message
 * for that field instead of being silently dropped (which would be reported as "missing").
 */
public final class EditorListingMapper {

    /** The listing JSON, or the messages of the fields that could not be read (keyed by form field name). */
    public record Mapped(ObjectNode listing, Map<String, String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private EditorListingMapper() {
    }

    public static Mapped map(EditorMarketplace marketplace, EditorForm form) {
        ObjectNode listing = JSON.objectNode();
        Map<String, String> errors = new LinkedHashMap<>();
        putText(listing, "title", form.title());
        putText(listing, "description", form.description());
        if (marketplace.isEtsy()) {
            putLines(listing, "tags", form.tags());
            putText(listing, "category", form.category());
            putText(listing, "who_made", form.whoMade());
            putText(listing, "when_made", form.whenMade());
        } else {
            putText(listing, "brand", form.brand());
            putLines(listing, "bullet_points", form.bulletPoints());
            putText(listing, "search_terms", form.searchTerms());
            putText(listing, "product_type", form.productType());
        }
        putPrice(listing, form.price(), errors);
        putQuantity(listing, form.quantity(), errors);
        return new Mapped(StandardListingAdapter.toStandardInput(marketplace.isEtsy() ? "ETSY" : "AMAZON", listing), errors);
    }

    /** One entry per line, kept as typed (the normalizer trims and drops blanks); {@code \r\n} and {@code \n} both end a line. */
    public static List<String> lines(String text) {
        return text.isEmpty() ? List.of() : List.of(text.split("\\R", -1));
    }

    private static void putText(ObjectNode listing, String name, String value) {
        if (!value.isBlank()) {
            listing.put(name, value);
        }
    }

    private static void putLines(ObjectNode listing, String name, String text) {
        List<String> lines = lines(text);
        if (lines.stream().allMatch(String::isBlank)) {
            return;
        }
        ArrayNode array = listing.putArray(name);
        lines.forEach(array::add);
    }

    private static void putPrice(ObjectNode listing, String text, Map<String, String> errors) {
        String value = text.trim();
        if (value.isEmpty()) {
            return;
        }
        try {
            new BigDecimal(value);
            listing.put("price", value);
        } catch (NumberFormatException e) {
            errors.put("price", "Enter the price as a number with a decimal point, for example 19.99.");
        }
    }

    private static void putQuantity(ObjectNode listing, String text, Map<String, String> errors) {
        String value = text.trim();
        if (value.isEmpty()) {
            return;
        }
        try {
            listing.put("quantity", Long.parseLong(value));
        } catch (NumberFormatException e) {
            errors.put("quantity", "Enter the quantity as a whole number, for example 5.");
        }
    }
}
