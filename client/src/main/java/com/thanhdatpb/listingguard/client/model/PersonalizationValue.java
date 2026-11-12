package com.thanhdatpb.listingguard.client.model;

import java.util.Objects;

/**
 * One field of an order as the buyer typed it.
 *
 * @param label optional name of the field, for example {@code name}
 * @param text  the text exactly as typed; never trimmed
 */
public record PersonalizationValue(String label, String text) {

    public PersonalizationValue {
        Objects.requireNonNull(text, "text");
    }

    /** A labelled value. */
    public static PersonalizationValue of(String label, String text) {
        return new PersonalizationValue(label, text);
    }

    /** A value without a label. */
    public static PersonalizationValue text(String text) {
        return new PersonalizationValue(null, text);
    }

    /** Never shows the text: it is personal data. */
    @Override
    public String toString() {
        return "PersonalizationValue[label=" + label + ", text=<hidden>]";
    }
}
