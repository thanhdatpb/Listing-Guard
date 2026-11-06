package com.thanhdatpb.listingguard.core.listing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListingTest {

    private static final Listing AMAZON_STYLE = new Listing("Title", "Description", List.of("a", "b"),
            new Money(new BigDecimal("9.99"), "USD"), "cat", List.of("m"), List.of("https://example.com/1.jpg"), 3,
            "i_did", "made_to_order", "Brand", List.of("bullet"), "terms", "LUGGAGE");

    @Test
    void withTitleChangesOnlyTheTitle() {
        Listing copy = AMAZON_STYLE.withTitle("Other");

        assertThat(copy.title()).isEqualTo("Other");
        assertThat(copy.withTitle("Title")).isEqualTo(AMAZON_STYLE);
        assertThat(AMAZON_STYLE.title()).isEqualTo("Title");
    }

    @Test
    void withTagsChangesOnlyTheTags() {
        Listing copy = AMAZON_STYLE.withTags(List.of("x"));

        assertThat(copy.tags()).containsExactly("x");
        assertThat(copy.withTags(List.of("a", "b"))).isEqualTo(AMAZON_STYLE);
        assertThat(AMAZON_STYLE.tags()).containsExactly("a", "b");
    }

    @Test
    void personalizationQuestionsAreEmptyWhenNotGivenAndNeverNull() {
        assertThat(AMAZON_STYLE.personalizationQuestions()).isEmpty();
        assertThat(new Listing("t", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
                .personalizationQuestions()).isEmpty();
    }

    @Test
    void theCopiesKeepThePersonalizationQuestions() {
        PersonalizationQuestion question = new PersonalizationQuestion(PersonalizationQuestion.DROPDOWN, "Colour", null, true,
                null, List.of("Red", "Blue"), null);
        Listing withQuestion = new Listing("Title", "Description", List.of("a"), null, null, List.of(), List.of(), 1, null,
                null, null, List.of(), null, null, null, List.of(question));

        assertThat(withQuestion.withTitle("Other").personalizationQuestions()).containsExactly(question);
        assertThat(withQuestion.withTags(List.of("x")).personalizationQuestions()).containsExactly(question);
    }

    @Test
    void customizationSurfacesAreEmptyWhenNotGivenAndNeverNull() {
        assertThat(AMAZON_STYLE.customizationSurfaces()).isEmpty();
        assertThat(new Listing("t", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null).customizationSurfaces()).isEmpty();
    }

    @Test
    void theCopiesKeepTheCustomizationSurfaces() {
        CustomizationSurface surface = new CustomizationSurface("Front",
                List.of(new CustomizationSurface.TextInput("Name", 20, List.of("letters"))),
                List.of(new CustomizationSurface.Dropdown("Font", List.of("Serif", "Script"))),
                List.of(new CustomizationSurface.ImageInput("Photo")));
        Listing withSurface = new Listing("Title", "Description", List.of("a"), null, null, List.of(), List.of(), 1, null,
                null, null, List.of(), null, null, null, List.of(), List.of(surface));

        assertThat(withSurface.withTitle("Other").customizationSurfaces()).containsExactly(surface);
        assertThat(withSurface.withTags(List.of("x")).customizationSurfaces()).containsExactly(surface);
    }

    @Test
    void aSurfaceAndItsInputsNeverHaveNullListsAndCopyWhatTheyAreGiven() {
        CustomizationSurface empty = new CustomizationSurface(null, null, null, null);
        assertThat(empty.textInputs()).isEmpty();
        assertThat(empty.dropdowns()).isEmpty();
        assertThat(empty.imageInputs()).isEmpty();
        assertThat(new CustomizationSurface.TextInput("Name", null, null).allowedCharTypes()).isEmpty();

        List<String> options = new java.util.ArrayList<>(List.of("Red"));
        CustomizationSurface.Dropdown dropdown = new CustomizationSurface.Dropdown("Colour", options);
        options.add("Blue");
        assertThat(dropdown.options()).containsExactly("Red");
        assertThat(new CustomizationSurface.Dropdown("Colour", null).options()).isEmpty();
    }

    @Test
    void aQuestionKeepsItsOptionsAndNeverHasNull() {
        List<String> options = new java.util.ArrayList<>(List.of("Red"));
        PersonalizationQuestion question = new PersonalizationQuestion("DROPDOWN", "Colour", null, null, null, options, null);
        options.add("Blue");

        assertThat(question.options()).containsExactly("Red");
        assertThat(new PersonalizationQuestion("TEXT", "Name", null, null, 20, null, null).options()).isEmpty();
    }
}
