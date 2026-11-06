package com.thanhdatpb.listingguard.api.personalization;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.personalization.PersonalizationInput;
import com.thanhdatpb.listingguard.personalization.Texts;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps the request to the values the rules read (docs/input-schema.md, "Personalization screening"). Like the other
 * normalizers it only maps and cleans and never dedupes, truncates, corrects or reorders. One difference, on purpose: the
 * <b>text of a value is not trimmed</b>, because leading, trailing and repeated spaces are what {@code whitespace_issue}
 * reports. It gets Unicode NFC and nothing else.
 *
 * <ul>
 *   <li>{@code print_profile}, {@code product_type} and a label are trimmed, and blank is absent; a number or boolean is read
 *       as its text; an object or array where text is expected is absent;
 *   <li>a value that is not an object, or has no {@code text} or an empty one, is dropped, so {@code values[i]} counts the
 *       cleaned list; a text of only spaces is kept;
 *   <li>a value that is not an array where an array is expected is an empty list.
 * </ul>
 */
@Component
public class PersonalizationNormalizer {

    public PersonalizationInput normalize(PersonalizationScreeningRequest request) {
        return new PersonalizationInput(trimmed(request.printProfile()), trimmed(request.productType()),
                values(request.values()));
    }

    private static List<PersonalizationInput.Value> values(JsonNode node) {
        List<PersonalizationInput.Value> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            String text = text(item.get("text"));
            if (text != null) {
                values.add(new PersonalizationInput.Value(trimmed(item.get("label")), text));
            }
        }
        return values;
    }

    /** The buyer's text exactly as sent after NFC, or null when absent, not a plain value or empty. */
    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = Texts.nfc(node.asText());
        return value.isEmpty() ? null : value;
    }

    /** A trimmed string value, or null when absent, null, not a plain value or blank. */
    private static String trimmed(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }
}
