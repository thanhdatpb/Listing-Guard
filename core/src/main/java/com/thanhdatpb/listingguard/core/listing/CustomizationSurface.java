package com.thanhdatpb.listingguard.core.listing;

import java.util.List;

/**
 * One print surface of an Amazon Custom product (for example the front or the back of a mug), with the inputs a buyer fills
 * in for it. Like the rest of {@link Listing} it is cleaned (trimmed, blank text dropped) but never corrected: whether a
 * surface is allowed is the job of the rules. Lists are never null.
 *
 * @param name the name of the surface; null when absent or blank
 */
public record CustomizationSurface(
        String name,
        List<TextInput> textInputs,
        List<Dropdown> dropdowns,
        List<ImageInput> imageInputs) {

    public CustomizationSurface {
        textInputs = textInputs == null ? List.of() : List.copyOf(textInputs);
        dropdowns = dropdowns == null ? List.of() : List.copyOf(dropdowns);
        imageInputs = imageInputs == null ? List.of() : List.copyOf(imageInputs);
    }

    /**
     * A text box the buyer types into.
     *
     * @param label the label the buyer sees; null when absent or blank
     * @param maxChars the longest text the seller allows; null when the seller set none
     * @param allowedCharTypes the kinds of characters the seller allows, as written (for example {@code letters}); empty when
     *                         the seller set none
     */
    public record TextInput(String label, Integer maxChars, List<String> allowedCharTypes) {

        public TextInput {
            allowedCharTypes = allowedCharTypes == null ? List.of() : List.copyOf(allowedCharTypes);
        }
    }

    /**
     * A dropdown whose choices the seller sets.
     *
     * @param label the label the buyer sees; null when absent or blank
     * @param options the choices, in the order sent, repeats kept
     */
    public record Dropdown(String label, List<String> options) {

        public Dropdown {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * A place where the buyer uploads an image.
     *
     * @param label the label the buyer sees; null when absent or blank
     */
    public record ImageInput(String label) {
    }
}
