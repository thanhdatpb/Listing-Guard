package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The heading and the body of a finding card (ADR-041): the message is split, never shortened. Every text here is invented. */
class FindingCardTest {

    private static FindingCard card(String message) {
        return new FindingCard("rules", "error", "Error", "error", true, "r-1", "title", message, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    @Test
    void aShortOneSentenceMessageIsTheHeadingAndHasNoBody() {
        FindingCard card = card("The title is too long.");

        assertThat(card.title()).isEqualTo("The title is too long.");
        assertThat(card.more()).isNull();
    }

    @Test
    void theFirstSentenceIsTheHeadingAndTheRestIsTheBody() {
        FindingCard card = card("Tag 4 repeats a word of the title. Shops lose a tag slot this way! Use a different word.");

        assertThat(card.title()).isEqualTo("Tag 4 repeats a word of the title.");
        assertThat(card.more()).isEqualTo("Shops lose a tag slot this way! Use a different word.");
    }

    @Test
    void aLongFirstSentenceIsCutAtAWordAndTheWholeMessageIsTheBody() {
        String message = "This title is longer than the shop's marketplace takes and the part after the limit is cut off by the site without a notice.";
        FindingCard card = card(message);

        assertThat(card.title()).endsWith("…").hasSizeLessThanOrEqualTo(FindingCard.TITLE_CHARS);
        assertThat(card.title().substring(0, card.title().length() - 1)).isNotEmpty();
        assertThat(message).startsWith(card.title().substring(0, card.title().length() - 1));
        assertThat(card.more()).as("nothing is dropped").isEqualTo(message);
    }

    @Test
    void aDotInsideAWordOrANumberDoesNotEndTheSentence() {
        FindingCard card = card("Use 3.5 mm or the 4.0 size of listing-rules v2.1 only. Second sentence.");

        assertThat(card.title()).isEqualTo("Use 3.5 mm or the 4.0 size of listing-rules v2.1 only.");
        assertThat(card.more()).isEqualTo("Second sentence.");
    }

    @Test
    void aMissingMessageIsAnEmptyHeading() {
        assertThat(card(null).title()).isEmpty();
        assertThat(card(null).more()).isNull();
    }
}
