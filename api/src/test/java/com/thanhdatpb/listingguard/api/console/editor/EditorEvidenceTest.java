package com.thanhdatpb.listingguard.api.console.editor;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.listing.Listing;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEvidenceTest {

    private static Listing etsy(String title, List<String> tags) {
        return new Listing(title, "description", tags, null, null, List.of(), List.of(), null, null, null);
    }

    @Test
    void aRuleValueIsShownWholeAndMarked() {
        EditorResult.Evidence evidence = EditorEvidence.ofValue("a tag that is far too long");

        assertThat(evidence.before()).isEmpty();
        assertThat(evidence.match()).isEqualTo("a tag that is far too long");
        assertThat(evidence.after()).isEmpty();
        assertThat(evidence.cutBefore()).isFalse();
        assertThat(evidence.cutAfter()).isFalse();
    }

    @Test
    void aFindingWithoutAValueHasNoEvidence() {
        assertThat(EditorEvidence.ofValue(null)).isNull();
        assertThat(EditorEvidence.ofValue("")).isNull();
    }

    @Test
    void aSpanIsCutOutWithContextAndTheCutsAreSaid() {
        String text = "a".repeat(100) + "Zorvex" + "b".repeat(100);

        EditorResult.Evidence evidence = EditorEvidence.ofSpan(text, 100, 106, "Zorvex");

        assertThat(evidence.match()).isEqualTo("Zorvex");
        assertThat(evidence.before()).isEqualTo("a".repeat(EditorEvidence.CONTEXT));
        assertThat(evidence.after()).isEqualTo("b".repeat(EditorEvidence.CONTEXT));
        assertThat(evidence.cutBefore()).isTrue();
        assertThat(evidence.cutAfter()).isTrue();
    }

    @Test
    void aSpanNearTheEdgesShowsAllTheTextAndNoCut() {
        EditorResult.Evidence evidence = EditorEvidence.ofSpan("Zorvex mug", 0, 6, "Zorvex");

        assertThat(evidence.before()).isEmpty();
        assertThat(evidence.match()).isEqualTo("Zorvex");
        assertThat(evidence.after()).isEqualTo(" mug");
        assertThat(evidence.cutBefore()).isFalse();
        assertThat(evidence.cutAfter()).isFalse();
    }

    @Test
    void offsetsCountUtf16UnitsSoAnEmojiBeforeTheSpanIsCountedTwice() {
        String text = "Café 😀 Zorvex fan";

        EditorResult.Evidence evidence = EditorEvidence.ofSpan(text, 8, 14, "Zorvex");

        assertThat(evidence.match()).isEqualTo("Zorvex");
        assertThat(evidence.before()).isEqualTo("Café 😀 ");
        assertThat(evidence.after()).isEqualTo(" fan");
    }

    @Test
    void theContextNeverSplitsASurrogatePair() {
        // the emoji is at units 10 and 11; the context window would start exactly on its second half
        String text = "x".repeat(10) + "😀" + "y".repeat(100);
        int start = 11 + EditorEvidence.CONTEXT;

        EditorResult.Evidence evidence = EditorEvidence.ofSpan(text, start, start + 1, "y");

        assertThat(evidence.before()).isNotEmpty();
        assertThat(Character.isLowSurrogate(evidence.before().charAt(0))).isFalse();
        assertThat(evidence.cutBefore()).isTrue();
    }

    @Test
    void aSpanThatDoesNotFitTheTextFallsBackToTheSnippetInsteadOfAWrongCut() {
        EditorResult.Evidence outside = EditorEvidence.ofSpan("short", 3, 40, "snippet");
        EditorResult.Evidence empty = EditorEvidence.ofSpan("short", 3, 3, "snippet");
        EditorResult.Evidence noText = EditorEvidence.ofSpan(null, 0, 2, "snippet");

        for (EditorResult.Evidence evidence : List.of(outside, empty, noText)) {
            assertThat(evidence.match()).isEqualTo("snippet");
            assertThat(evidence.before()).isEmpty();
            assertThat(evidence.after()).isEmpty();
        }
        assertThat(EditorEvidence.ofSpan(null, 0, 2, null)).isNull();
    }

    @Test
    void theFieldTextIsTheNormalizedValueOfTheFieldInNfc() {
        Listing listing = etsy("Title", List.of("first", "café"));

        assertThat(EditorEvidence.fieldText(listing, "title")).isEqualTo("Title");
        assertThat(EditorEvidence.fieldText(listing, "tags[0]")).isEqualTo("first");
        // decomposed e + combining acute becomes the single composed character, as the scanner sees it
        assertThat(EditorEvidence.fieldText(listing, "tags[1]")).isEqualTo("café");
        assertThat(EditorEvidence.fieldText(listing, "tags[2]")).isNull();
        assertThat(EditorEvidence.fieldText(listing, "description")).isEqualTo("description");
        assertThat(EditorEvidence.fieldText(listing, "unknown_field")).isNull();
        assertThat(EditorEvidence.fieldText(listing, null)).isNull();
    }

    @Test
    void bulletPointsAndAmazonFieldsAreFound() {
        Listing listing = new Listing("T", "D", List.of(), null, null, List.of(), List.of(), null, null, null, "Acme",
                List.of("one", "two"), "carry on", "LUGGAGE");

        assertThat(EditorEvidence.fieldText(listing, "bullet_points[1]")).isEqualTo("two");
        assertThat(EditorEvidence.fieldText(listing, "brand")).isEqualTo("Acme");
        assertThat(EditorEvidence.fieldText(listing, "search_terms")).isEqualTo("carry on");
    }
}
