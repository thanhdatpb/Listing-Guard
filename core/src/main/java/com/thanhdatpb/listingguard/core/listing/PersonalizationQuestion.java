package com.thanhdatpb.listingguard.core.listing;

import java.util.List;

/**
 * One structured personalization question of an Etsy listing, as the seller wrote it. Like the rest of {@link Listing} it is
 * cleaned (trimmed, blank text dropped) but never corrected: whether a question is allowed is the job of the rules.
 *
 * <p>{@code type} is {@link #TEXT}, {@link #DROPDOWN} or {@link #UPLOAD} (upper case), or whatever the seller sent when it is
 * none of them; a missing type is {@code null}. {@code options} are the choices of a dropdown, or the labels of the files of an
 * upload question: an upload with options is a <em>labeled</em> upload, one without is an unlabeled one. A component that does
 * not belong to the type (for example {@code maxChars} on a dropdown) is kept as sent. Absent numbers and flags are {@code null}.
 *
 * @param questionText the text the buyer sees; null when absent or blank
 * @param instructions optional help text under the question; null when absent or blank
 * @param required whether the buyer must answer; null when the seller did not say
 * @param maxChars the longest answer of a text question
 * @param maxFiles the number of files an upload question accepts
 */
public record PersonalizationQuestion(
        String type,
        String questionText,
        String instructions,
        Boolean required,
        Integer maxChars,
        List<String> options,
        Integer maxFiles) {

    public static final String TEXT = "TEXT";
    public static final String DROPDOWN = "DROPDOWN";
    public static final String UPLOAD = "UPLOAD";

    public PersonalizationQuestion {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
