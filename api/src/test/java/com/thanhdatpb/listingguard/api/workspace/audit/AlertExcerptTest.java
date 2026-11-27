package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The short piece an alert e-mail quotes (ADR-061). */
class AlertExcerptTest {

    private static final String TEXT = "A very long description of a soft hoodie with a ZORVEX print on the front and a plain back for the cold days";

    @Test
    void itQuotesTheMatchAndAFewCharactersOnEachSideAndMarksWhatWasLeftOut() {
        int at = TEXT.indexOf("ZORVEX");

        String excerpt = AlertExcerpt.of(TEXT, at, at + 6, 12);

        assertThat(excerpt).contains("ZORVEX").startsWith("…").endsWith("…");
        assertThat(excerpt.length()).isLessThan(TEXT.length() / 2);
        assertThat(TEXT).contains(excerpt.replace("…", "").strip());
    }

    @Test
    void itNeverReturnsTheWholeTextOfALongListingAndKeepsAShortOneWhole() {
        assertThat(AlertExcerpt.of(TEXT, 0, 4, 5)).doesNotContain("hoodie").endsWith("…");
        assertThat(AlertExcerpt.of("Zorvex mug", 0, 6, 40)).isEqualTo("Zorvex mug");
    }

    @Test
    void aSpanThatDoesNotFitOrIsUnknownHasNoExcerptAndSurrogatesAreNotCut() {
        assertThat(AlertExcerpt.of(TEXT, null, null, 10)).isNull();
        assertThat(AlertExcerpt.of(TEXT, 5, 5, 10)).isNull();
        assertThat(AlertExcerpt.of(TEXT, 5, 9999, 10)).isNull();
        assertThat(AlertExcerpt.of(null, 0, 1, 10)).isNull();
        String emoji = "😀😀 ZORVEX 😀😀";
        int at = emoji.indexOf("ZORVEX");
        assertThat(AlertExcerpt.of(emoji, at, at + 6, 1)).contains("ZORVEX").matches(s -> !s.contains("�"));
    }

    @Test
    void whiteSpaceIsCollapsedSoARowStaysOnOneLine() {
        assertThat(AlertExcerpt.of("one\n\n  two   ZORVEX\tthree", 12, 18, 30)).isEqualTo("one two ZORVEX three");
    }
}
