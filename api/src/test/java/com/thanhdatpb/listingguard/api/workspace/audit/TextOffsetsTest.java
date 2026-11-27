package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.workspace.audit.TextOffsets.Span;
import java.text.Normalizer;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The span of a finding in the stored text, for punctuation, case, extra spaces and decomposed accents (ADR-061). No database. */
class TextOffsetsTest {

    private static String cut(String text, Optional<Span> span) {
        assertThat(span).isPresent();
        return text.substring(span.get().start(), span.get().end());
    }

    @Test
    void aRuleSpanIsTheSameTextWhenTheStoredTextHasNoLeadingSpace() {
        String stored = "Cool Zorvex Shirt, WOW!";
        int at = stored.indexOf("WOW");

        assertThat(cut(stored, TextOffsets.fromRule(stored, at, at + 3))).isEqualTo("WOW");
    }

    @Test
    void aRuleSpanSkipsTheLeadingSpaceTheNormalizerTrimmed() {
        String stored = "   free  shipping now";
        String trimmed = stored.trim();
        int at = trimmed.indexOf("shipping");

        assertThat(cut(stored, TextOffsets.fromRule(stored, at, at + 8))).isEqualTo("shipping");
    }

    @Test
    void aRuleSpanPastTheTrimmedTextIsNoPosition() {
        assertThat(TextOffsets.fromRule("abc", 2, 9)).isEmpty();
        assertThat(TextOffsets.fromRule("abc", -1, 2)).isEmpty();
        assertThat(TextOffsets.fromRule("abc", 2, 2)).isEmpty();
        assertThat(TextOffsets.fromRule(null, 0, 1)).isEmpty();
    }

    @Test
    void aCanonicalSpanIsTheStoredWordWithPunctuationCaseAndExtraSpaces() {
        String stored = "  Nice,   ZORVEX!!  Hoodie -- Zorvex's best ";
        String canonical = Normalizer.normalize(stored, Normalizer.Form.NFC).strip();
        int at = canonical.indexOf("ZORVEX");

        assertThat(cut(stored, TextOffsets.fromCanonical(stored, at, at + 6))).isEqualTo("ZORVEX");
        int second = canonical.indexOf("Zorvex's");
        assertThat(cut(stored, TextOffsets.fromCanonical(stored, second, second + 8))).isEqualTo("Zorvex's");
    }

    @Test
    void aCanonicalSpanFollowsADecomposedAccentThatNfcMakesShorter() {
        // "Crème" and "brûlée" typed with combining accents: the canonical text is shorter than the stored text
        String stored = "Crème brûlée Zorvex mug";
        String canonical = Normalizer.normalize(stored, Normalizer.Form.NFC).strip();
        assertThat(canonical.length()).isLessThan(stored.length());
        int at = canonical.indexOf("Zorvex");

        assertThat(cut(stored, TextOffsets.fromCanonical(stored, at, at + 6))).isEqualTo("Zorvex");
        int accented = canonical.indexOf("brûlée");
        assertThat(cut(stored, TextOffsets.fromCanonical(stored, accented, accented + 6))).isEqualTo("brûlée");
    }

    @Test
    void aCanonicalSpanSurvivesEmojiAndSurrogatePairs() {
        String stored = "😀 Happy 👍 Zorvex 🎉";
        String canonical = stored.strip();
        int at = canonical.indexOf("Zorvex");

        assertThat(cut(stored, TextOffsets.fromCanonical(stored, at, at + 6))).isEqualTo("Zorvex");
    }

    @Test
    void aSpanThatStartsInsideAClusterIsWidenedToTheWholeClusterNeverNarrowed() {
        String stored = "café x";
        // offset 3 is the "e acute"; a span that covers only a part of a cluster is widened to it
        assertThat(cut(stored, TextOffsets.fromCanonical(stored, 3, 4))).isEqualTo("é");
        String decomposed = "café x";
        assertThat(cut(decomposed, TextOffsets.fromCanonical(decomposed, 3, 4))).isEqualTo("é");
    }

    @Test
    void aSpanBeyondTheCanonicalTextOrAnEmptyOneIsNoPosition() {
        assertThat(TextOffsets.fromCanonical("abc", 1, 10)).isEmpty();
        assertThat(TextOffsets.fromCanonical("abc", 2, 2)).isEmpty();
        assertThat(TextOffsets.fromCanonical("", 0, 1)).isEmpty();
    }
}
