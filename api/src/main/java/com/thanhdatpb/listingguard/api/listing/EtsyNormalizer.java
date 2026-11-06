package com.thanhdatpb.listingguard.api.listing;

import static com.thanhdatpb.listingguard.api.listing.JsonFields.bool;
import static com.thanhdatpb.listingguard.api.listing.JsonFields.firstNonNull;
import static com.thanhdatpb.listingguard.api.listing.JsonFields.integer;
import static com.thanhdatpb.listingguard.api.listing.JsonFields.price;
import static com.thanhdatpb.listingguard.api.listing.JsonFields.strings;
import static com.thanhdatpb.listingguard.api.listing.JsonFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.PersonalizationQuestion;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Normalizes Etsy Open API v3 style listing payloads (snake_case fields). Only maps and cleans
 * values; it never enforces marketplace limits.
 *
 * <p>The optional {@code personalization} block is read as {@code {"questions": [...]}}. Each question is an object (anything
 * else in the array is dropped); its text is trimmed and blank text is absent, its {@code type} is upper-cased, and nothing is
 * corrected, so an overlong text or a repeated option reaches the rules as the seller wrote it.
 */
@Component
public class EtsyNormalizer implements Normalizer {

    public static final String MARKETPLACE = "ETSY";

    @Override
    public String marketplace() {
        return MARKETPLACE;
    }

    @Override
    public Listing normalize(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new IllegalArgumentException("Etsy listing must be a JSON object");
        }
        return new Listing(
                text(raw.get("title")),
                text(raw.get("description")),
                strings(raw.get("tags")),
                price(raw.get("price")),
                category(raw),
                strings(raw.get("materials")),
                images(raw.get("images")),
                integer(raw.get("quantity")),
                text(raw.get("who_made")),
                text(raw.get("when_made")),
                null, List.of(), null, null, null,
                personalizationQuestions(raw.get("personalization")));
    }

    private static List<PersonalizationQuestion> personalizationQuestions(JsonNode block) {
        List<PersonalizationQuestion> out = new ArrayList<>();
        JsonNode questions = block == null || !block.isObject() ? null : block.get("questions");
        if (questions == null || !questions.isArray()) {
            return out;
        }
        for (JsonNode item : questions) {
            if (!item.isObject()) {
                continue;
            }
            String type = text(item.get("type"));
            out.add(new PersonalizationQuestion(
                    type == null ? null : type.toUpperCase(Locale.ROOT),
                    text(item.get("question_text")),
                    text(item.get("instructions")),
                    bool(item.get("required")),
                    integer(item.get("max_chars")),
                    strings(item.get("options")),
                    integer(item.get("max_files"))));
        }
        return out;
    }

    private static List<String> images(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            String url = item.isObject() ? firstNonNull(text(item.get("url_fullxfull")), text(item.get("url"))) : text(item);
            if (url != null) {
                out.add(url);
            }
        }
        return out;
    }

    private static String category(JsonNode raw) {
        String category = text(raw.get("category"));
        if (category != null) {
            return category;
        }
        List<String> path = strings(raw.get("taxonomy_path"));
        return path.isEmpty() ? null : String.join(" > ", path);
    }
}
