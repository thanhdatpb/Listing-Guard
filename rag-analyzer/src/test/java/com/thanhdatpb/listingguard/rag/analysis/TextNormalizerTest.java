package com.thanhdatpb.listingguard.rag.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TextNormalizerTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("plain text unchanged", "Etsy sellers", "Etsy sellers"),
                Arguments.of("trims the ends", "  Etsy sellers  ", "Etsy sellers"),
                Arguments.of("tabs and newlines become one space", "a\t\n\r\nb", "a b"),
                Arguments.of("NBSP becomes a space", "a  b", "a b"),
                Arguments.of("narrow NBSP and ideographic space", "a b　c", "a b c"),
                Arguments.of("line separator", "a b", "a b"),
                Arguments.of("curly single quotes", "‘it’s’", "'it's'"),
                Arguments.of("curly double quotes", "“quoted” „low‟", "\"quoted\" \"low\""),
                Arguments.of("en dash", "2020–2021", "2020-2021"),
                Arguments.of("em dash", "yes—no", "yes-no"),
                Arguments.of("ellipsis character becomes three dots", "wait…", "wait..."),
                Arguments.of("ligature", "ﬁnished", "finished"),
                Arguments.of("full-width letters", "ＡＢＣ", "ABC"),
                Arguments.of("case is kept", "MixedCase", "MixedCase"),
                Arguments.of("hyphen is kept", "a-b", "a-b"),
                Arguments.of("Vietnamese stays", "Đồ thủ công", "Đồ thủ công"),
                Arguments.of("decomposed Vietnamese equals composed", "ế", "ế"),
                Arguments.of("empty", "", ""),
                Arguments.of("only whitespace", "  \n", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void normalizes(String name, String input, String expected) {
        assertThat(TextNormalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    void bothSidesOfAComparisonEndUpEqual() {
        assertThat(TextNormalizer.normalize("Etsy — “clear” text"))
                .isEqualTo(TextNormalizer.normalize("Etsy - \"clear\" text"));
    }
}
