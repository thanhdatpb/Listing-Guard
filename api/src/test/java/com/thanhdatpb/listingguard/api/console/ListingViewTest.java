package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.console.ListingView.Mark;
import com.thanhdatpb.listingguard.api.console.ListingView.Segment;
import com.thanhdatpb.listingguard.core.listing.Listing;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The listing as it was checked (ADR-041): where the highlight goes, and where it never goes. Every text here is invented. */
class ListingViewTest {

    private static Listing etsy(String title, String description, String... tags) {
        return new Listing(title, description, List.of(tags), null, null, List.of(), List.of(), null, null, null, null, List.of(), null,
                null, null, List.of());
    }

    private static Listing amazon(String title, String... bullets) {
        return new Listing(title, null, List.of(), null, null, List.of(), List.of(), null, null, null, null, List.of(bullets), null,
                null, null, List.of());
    }

    private static String shown(List<Segment> segments) {
        StringBuilder text = new StringBuilder();
        segments.forEach(segment -> text.append(segment.tone().isEmpty() ? segment.text() : "[" + segment.tone() + ":" + segment.text() + "]"));
        return text.toString();
    }

    @Test
    void aTrademarkSpanMarksTheWordsInTheTitleAndNothingIsLost() {
        ListingView view = ListingView.of(etsy("Zorvex blue mug", null), true, List.of(Mark.span("title", 0, 6, "trademark")), 140, 13);

        assertThat(shown(view.title())).isEqualTo("[trademark:Zorvex] blue mug");
        assertThat(view.titleChars()).isEqualTo(15);
        assertThat(view.titleMax()).isEqualTo(140);
        assertThat(view.itemsKind()).isEqualTo("tags");
    }

    @Test
    void aRuleValueIsFoundInTheTitleButAValueThatIsTheWholeTitleMarksNothing() {
        ListingView part = ListingView.of(etsy("Mug mug mug", null), true, List.of(ListingView.ruleMark("title", "mug mug", "error")), null, null);
        ListingView whole = ListingView.of(etsy("Mug mug mug", null), true, List.of(ListingView.ruleMark("title", "Mug mug mug", "error")), null,
                null);

        assertThat(shown(part.title())).isEqualTo("Mug [error:mug mug]");
        assertThat(shown(whole.title())).as("all of the field says nothing about where").isEqualTo("Mug mug mug");
    }

    @Test
    void aSpanThatDoesNotFitIsDroppedAndAnInformationFindingMarksNothing() {
        ListingView view = ListingView.of(etsy("Short", null), true,
                List.of(Mark.span("title", 3, 99, "trademark"), Mark.span("title", 4, 2, "trademark")), null, null);

        assertThat(shown(view.title())).isEqualTo("Short");
        assertThat(ListingView.ruleMark("title", "Short", "info")).isNull();
        assertThat(ListingView.ruleMark("title", " ", "error")).isNull();
    }

    @Test
    void anErrorWinsWhereTwoMarksOverlap() {
        ListingView view = ListingView.of(etsy("Zorvex mug", null), true,
                List.of(Mark.span("title", 0, 6, "trademark"), Mark.span("title", 3, 8, "error")), null, null);

        assertThat(shown(view.title())).isEqualTo("Zor[error:vex m]ug");
    }

    @Test
    void aTagIsFlaggedAsAWholeAndAnErrorFlagWinsOverATrademarkOne() {
        ListingView view = ListingView.of(etsy("Mug", null, "plain", "zorvex", "very long tag"), true,
                List.of(Mark.span("tags[1]", 0, 6, "trademark"), ListingView.ruleMark("tags[2]", "very long tag", "error"),
                        Mark.span("tags[2]", 0, 4, "trademark")), null, 13);

        assertThat(view.items()).extracting(ListingView.Item::text).containsExactly("plain", "zorvex", "very long tag");
        assertThat(view.items()).extracting(ListingView.Item::flag).containsExactly("", "flag", "error");
        assertThat(view.itemsMax()).isEqualTo(13);
    }

    @Test
    void anAmazonListingShowsItsBulletPointsWithTheWordsMarked() {
        ListingView view = ListingView.of(amazon("Bottle", "Holds Zorvex water"), false,
                List.of(Mark.span("bullet_points[0]", 6, 12, "trademark")), null, null);

        assertThat(view.itemsKind()).isEqualTo("bullets");
        assertThat(shown(view.items().get(0).segments())).isEqualTo("Holds [trademark:Zorvex] water");
        assertThat(view.items().get(0).flag()).isEqualTo("flag");
    }

    @Test
    void theDescriptionIsCutAndTheTextIsNormalizedLikeTheScannerSawIt() {
        String longText = "word ".repeat(100);
        ListingView view = ListingView.of(etsy("éclair", longText), true, List.of(), null, null);

        assertThat(view.description()).hasSizeLessThanOrEqualTo(ListingView.DESCRIPTION_CHARS + 3).endsWith("...");
        assertThat(shown(view.title())).as("NFC: the accent is one character").isEqualTo("éclair");
        assertThat(view.titleChars()).isEqualTo(6);
        assertThat(view.hasContent()).isTrue();
        assertThat(ListingView.of(etsy(null, null), true, List.of(), null, null).hasContent()).isFalse();
    }
}
