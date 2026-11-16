package com.thanhdatpb.listingguard.api.llm;

import java.util.Locale;

/**
 * The kinds of data a prompt can contain (ADR-036 item 6). A provider receives a call only if it is allowed to see every kind the
 * call declares, so the kinds are what the data routing is decided on, never the provider's name.
 */
public enum LlmDataClass {

    /** Title, tags, bullet points, description, brand or search terms of a client's listing: not public until it is published. */
    LISTING_TEXT,

    /** Text a buyer types or the personalization instructions of a listing (ADR-016): personal data. */
    PERSONALIZATION;

    /** The kind of a scanner field name such as {@code title}, {@code tags[2]} or {@code personalization_instructions}. */
    public static LlmDataClass ofField(String field) {
        return field != null && field.toLowerCase(Locale.ROOT).startsWith("personalization") ? PERSONALIZATION : LISTING_TEXT;
    }
}
