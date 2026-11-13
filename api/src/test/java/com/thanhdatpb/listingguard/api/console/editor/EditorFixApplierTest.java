package com.thanhdatpb.listingguard.api.console.editor;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleMessagesTestSupport;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

class EditorFixApplierTest {

    private static final ConsoleMessages MESSAGES = ConsoleMessagesTestSupport.messages();

    @BeforeEach
    void english() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static EditorForm form(String title, String tags) {
        return new EditorForm("ETSY", title, tags, "A synthetic description.", "", "19.99", "5", "Home", "i_did",
                "made_to_order", "", "", "");
    }

    @Test
    void aTitleIsReplacedByTheProposedValueWhenItStillHoldsTheValueOfTheFinding() {
        EditorForm before = form("  A long synthetic title  ", "one\ntwo");

        EditorFixApplier.Outcome outcome = EditorFixApplier.apply(MESSAGES, before, "title", "A long synthetic title", "REPLACE",
                "A long synthetic");

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.form().title()).isEqualTo("A long synthetic");
        assertThat(outcome.message()).contains("Replaced the title").contains("press Check");
        // nothing else moved
        assertThat(outcome.form().withTitle(before.title())).isEqualTo(before);
    }

    @Test
    void aTitleTheSellerHasTypedOverSinceTheCheckIsNotOverwritten() {
        EditorForm before = form("A different title now", "");

        EditorFixApplier.Outcome outcome = EditorFixApplier.apply(MESSAGES, before, "title", "A long synthetic title", "REPLACE", "A long");

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.form()).isEqualTo(before);
        assertThat(outcome.message()).contains("changed since the check");
    }

    @Test
    void aTagIsReplacedAtItsCleanedIndexAndTheOtherLinesStayAsTyped() {
        // line 1 is blank, so the cleaned list is [one, a very long tag, two] and the finding says tags[1]
        EditorForm before = form("Title", "one\n\n  a very long tag  \ntwo");

        EditorFixApplier.Outcome outcome = EditorFixApplier.apply(MESSAGES, before, "tags[1]", "a very long tag", "REPLACE", "a very");

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.form().tags()).isEqualTo("one\n\na very\ntwo");
        assertThat(outcome.message()).contains("a very long tag").contains("a very");
    }

    @Test
    void aRepeatedTagIsRemovedAndTheFirstOccurrenceStays() {
        EditorForm before = form("Title", "mug\nceramic\nMug\nlast");

        // duplicate_tags points at the repeat, tags[2]
        EditorFixApplier.Outcome outcome = EditorFixApplier.apply(MESSAGES, before, "tags[2]", "Mug", "REMOVE", null);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.form().tags()).isEqualTo("mug\nceramic\nlast");
        assertThat(outcome.message()).contains("Removed the tag \"Mug\"");
    }

    @Test
    void aTagThatMovedIsFoundByItsValueWhenOnlyOneLineHoldsIt() {
        // the seller deleted the first line after the check: the index no longer fits, the value is still unique
        EditorForm before = form("Title", "ceramic\nthe long tag");

        EditorFixApplier.Outcome outcome = EditorFixApplier.apply(MESSAGES, before, "tags[2]", "the long tag", "REPLACE", "the long");

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.form().tags()).isEqualTo("ceramic\nthe long");
    }

    @Test
    void aTagThatIsNoLongerThereOrIsAmbiguousIsNotTouched() {
        EditorForm gone = form("Title", "ceramic");
        EditorForm twice = form("Title", "same\nsame");

        EditorFixApplier.Outcome missing = EditorFixApplier.apply(MESSAGES, gone, "tags[3]", "the long tag", "REPLACE", "the long");
        EditorFixApplier.Outcome ambiguous = EditorFixApplier.apply(MESSAGES, twice, "tags[5]", "same", "REMOVE", null);

        assertThat(missing.applied()).isFalse();
        assertThat(missing.form()).isEqualTo(gone);
        assertThat(ambiguous.applied()).isFalse();
        assertThat(ambiguous.form()).isEqualTo(twice);
    }

    @Test
    void aTagValueTheSellerEditedAtTheSameIndexIsNotOverwritten() {
        EditorForm before = form("Title", "one\nan edited tag");

        EditorFixApplier.Outcome outcome = EditorFixApplier.apply(MESSAGES, before, "tags[1]", "the old long tag", "REPLACE", "the old");

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.form().tags()).isEqualTo("one\nan edited tag");
    }

    @Test
    void onlyATitleAndATagCanBeFilledAndNothingElseIsEverTouched() {
        EditorForm before = form("Title", "one");

        for (String field : new String[] {"description", "bullet_points[0]", "price", "search_terms", "brand", "unknown"}) {
            EditorFixApplier.Outcome outcome = EditorFixApplier.apply(MESSAGES, before, field, "x", "REPLACE", "y");

            assertThat(outcome.applied()).as(field).isFalse();
            assertThat(outcome.form()).as(field).isEqualTo(before);
            assertThat(outcome.message()).as(field).contains("cannot be applied");
        }
        assertThat(EditorFixApplier.apply(MESSAGES, before, "title", "Title", "REMOVE", null).applied()).isFalse();
    }

    @Test
    void aProposalThatCannotBePutInASingleLineFieldIsRefused() {
        EditorForm before = form("Title", "one");

        for (String proposed : new String[] {null, "", "  ", "two\nlines", "two\rlines"}) {
            assertThat(EditorFixApplier.apply(MESSAGES, before, "title", "Title", "REPLACE", proposed).applied())
                    .as("title " + proposed).isFalse();
            assertThat(EditorFixApplier.apply(MESSAGES, before, "tags[0]", "one", "REPLACE", proposed).applied())
                    .as("tag " + proposed).isFalse();
        }
    }

    @Test
    void missingOrUnknownPartsOfASuggestionAreRefusedNotGuessed() {
        EditorForm before = form("Title", "one");

        assertThat(EditorFixApplier.apply(MESSAGES, before, null, "Title", "REPLACE", "T").applied()).isFalse();
        assertThat(EditorFixApplier.apply(MESSAGES, before, "title", null, "REPLACE", "T").applied()).isFalse();
        assertThat(EditorFixApplier.apply(MESSAGES, before, "title", "Title", null, "T").applied()).isFalse();
        assertThat(EditorFixApplier.apply(MESSAGES, before, "title", "Title", "ADD", "T").applied()).isFalse();
    }
}
