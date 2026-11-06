package com.thanhdatpb.listingguard.personalization;

import java.util.List;

/**
 * What the pack reads (ADR-016): the text a buyer typed for a custom product, one value per field of the order.
 * {@code printProfile} is the code the client named, or null for the ruleset's default; {@code productType} is free text
 * that is hashed and not read by any rule yet. The marketplace is not part of the input: it belongs to the cache identity.
 *
 * <p>The text of a value is exactly what the buyer typed after Unicode NFC. It is <em>not</em> stripped, because leading,
 * trailing and repeated spaces are what {@code whitespace_issue} reports. Offsets in findings are UTF-16 code units in it.
 */
public record PersonalizationInput(String printProfile, String productType, List<Value> values) {

    /** @param label optional name of the field the buyer filled ("name", "number"); @param text what the buyer typed */
    public record Value(String label, String text) {
    }

    public PersonalizationInput {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
