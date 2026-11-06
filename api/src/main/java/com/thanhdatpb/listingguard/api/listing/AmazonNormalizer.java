package com.thanhdatpb.listingguard.api.listing;

import static com.thanhdatpb.listingguard.api.listing.JsonFields.integer;
import static com.thanhdatpb.listingguard.api.listing.JsonFields.price;
import static com.thanhdatpb.listingguard.api.listing.JsonFields.strings;
import static com.thanhdatpb.listingguard.api.listing.JsonFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.core.listing.CustomizationSurface;
import com.thanhdatpb.listingguard.core.listing.Listing;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Normalizes the simple Amazon listing format: {@code title}, {@code brand}, {@code bullet_points} (array),
 * {@code description}, {@code search_terms} (string, or array joined by a single space), {@code product_type},
 * {@code price}, {@code quantity}, {@code images} (array of URLs) and {@code item_highlights} (string). Only maps and
 * cleans values; it never enforces
 * marketplace limits. The product type is kept as written: matching it to the ruleset ignores case.
 *
 * <p>The optional {@code customization} block (Amazon Custom) is read as {@code {"surfaces": [...]}}. Each surface is an object
 * (anything else in the array is dropped) with {@code name}, {@code text_inputs} ({@code label}, {@code max_chars},
 * {@code allowed_char_types}), {@code dropdowns} ({@code label}, {@code options}) and {@code image_inputs} ({@code label}); an
 * entry of those lists that is not an object is dropped too. Text is trimmed and blank text is absent, and nothing is
 * corrected, so a repeated option or a missing {@code max_chars} reaches the rules as the seller wrote it.
 */
@Component
public class AmazonNormalizer implements Normalizer {

    public static final String MARKETPLACE = "AMAZON";

    @Override
    public String marketplace() {
        return MARKETPLACE;
    }

    @Override
    public Listing normalize(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new IllegalArgumentException("Amazon listing must be a JSON object");
        }
        return new Listing(
                text(raw.get("title")),
                text(raw.get("description")),
                List.of(),
                price(raw.get("price")),
                null,
                List.of(),
                strings(raw.get("images")),
                integer(raw.get("quantity")),
                null,
                null,
                text(raw.get("brand")),
                strings(raw.get("bullet_points")),
                searchTerms(raw.get("search_terms")),
                text(raw.get("product_type")),
                text(raw.get("item_highlights")),
                List.of(),
                customizationSurfaces(raw.get("customization")));
    }

    private static List<CustomizationSurface> customizationSurfaces(JsonNode block) {
        List<CustomizationSurface> out = new ArrayList<>();
        JsonNode surfaces = block == null || !block.isObject() ? null : block.get("surfaces");
        if (surfaces == null || !surfaces.isArray()) {
            return out;
        }
        for (JsonNode item : surfaces) {
            if (!item.isObject()) {
                continue;
            }
            out.add(new CustomizationSurface(
                    text(item.get("name")),
                    textInputs(item.get("text_inputs")),
                    dropdowns(item.get("dropdowns")),
                    imageInputs(item.get("image_inputs"))));
        }
        return out;
    }

    private static List<CustomizationSurface.TextInput> textInputs(JsonNode node) {
        List<CustomizationSurface.TextInput> out = new ArrayList<>();
        for (JsonNode item : objects(node)) {
            out.add(new CustomizationSurface.TextInput(
                    text(item.get("label")), integer(item.get("max_chars")), strings(item.get("allowed_char_types"))));
        }
        return out;
    }

    private static List<CustomizationSurface.Dropdown> dropdowns(JsonNode node) {
        List<CustomizationSurface.Dropdown> out = new ArrayList<>();
        for (JsonNode item : objects(node)) {
            out.add(new CustomizationSurface.Dropdown(text(item.get("label")), strings(item.get("options"))));
        }
        return out;
    }

    private static List<CustomizationSurface.ImageInput> imageInputs(JsonNode node) {
        List<CustomizationSurface.ImageInput> out = new ArrayList<>();
        for (JsonNode item : objects(node)) {
            out.add(new CustomizationSurface.ImageInput(text(item.get("label"))));
        }
        return out;
    }

    /** The entries of an array that are objects; anything else, or a value that is not an array, gives none. */
    private static List<JsonNode> objects(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isObject()) {
                    out.add(item);
                }
            });
        }
        return out;
    }

    private static String searchTerms(JsonNode node) {
        if (node != null && node.isArray()) {
            List<String> terms = strings(node);
            return terms.isEmpty() ? null : String.join(" ", terms);
        }
        return text(node);
    }
}
