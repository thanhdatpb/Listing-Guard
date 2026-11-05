package com.thanhdatpb.listingguard.api.console.editor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EditorPreviewTest {

    private static EditorForm withTitle(String title) {
        return EditorForm.empty(EditorMarketplace.ETSY).withTitle(title);
    }

    @Test
    void aTitleWithinTheLimitIsShownWholeAndNothingIsBeyond() {
        EditorPreview preview = EditorPreview.of(withTitle("  Short title  "), 20);

        assertThat(preview.titleWithin()).isEqualTo("Short title");
        assertThat(preview.titleBeyond()).isEmpty();
        assertThat(preview.overTheLimit()).isFalse();
        assertThat(preview.title()).isEqualTo("Short title");
    }

    @Test
    void aTitleExactlyAtTheLimitIsNotBeyond() {
        EditorPreview preview = EditorPreview.of(withTitle("a".repeat(20)), 20);

        assertThat(preview.titleBeyond()).isEmpty();
    }

    @Test
    void theTitlePastTheLimitIsSetApartAndTheWholeTitleIsStillThere() {
        EditorPreview preview = EditorPreview.of(withTitle("a".repeat(20) + "BEYOND"), 20);

        assertThat(preview.titleWithin()).isEqualTo("a".repeat(20));
        assertThat(preview.titleBeyond()).isEqualTo("BEYOND");
        assertThat(preview.overTheLimit()).isTrue();
        assertThat(preview.title()).isEqualTo("a".repeat(20) + "BEYOND");
    }

    @Test
    void theLimitCountsCodePointsSoAnEmojiIsOneAndIsNeverSplit() {
        String title = "ab😀cd";

        EditorPreview preview = EditorPreview.of(withTitle(title), 3);

        assertThat(preview.titleWithin()).isEqualTo("ab😀");
        assertThat(preview.titleBeyond()).isEqualTo("cd");
    }

    @Test
    void withNoLimitThereIsNothingBeyond() {
        EditorPreview preview = EditorPreview.of(withTitle("x".repeat(1000)), null);

        assertThat(preview.titleBeyond()).isEmpty();
        assertThat(preview.hasTitle()).isTrue();
    }

    @Test
    void anEmptyTitleHasNoTitle() {
        assertThat(EditorPreview.of(withTitle("   "), 20).hasTitle()).isFalse();
    }

    @Test
    void bulletPointsAreTheCleanedLinesAndTheOtherTextIsTrimmed() {
        EditorForm form = new EditorForm("AMAZON", "T", "", "  A description.  ", " one \n\n two ", " 89.99 ", "", "", "", "",
                " Acme ", "", "");

        EditorPreview preview = EditorPreview.of(form, 200);

        assertThat(preview.bullets()).containsExactly("one", "two");
        assertThat(preview.description()).isEqualTo("A description.");
        assertThat(preview.price()).isEqualTo("89.99");
        assertThat(preview.brand()).isEqualTo("Acme");
    }
}
