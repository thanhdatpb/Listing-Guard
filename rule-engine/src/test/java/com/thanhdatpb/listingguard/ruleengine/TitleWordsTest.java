package com.thanhdatpb.listingguard.ruleengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TitleWordsTest {

    @Test
    void countsLettersAndDigitsAsWordsAndPunctuationAsASeparator() {
        assertThat(TitleWords.count("")).isZero();
        assertThat(TitleWords.count("   ")).isZero();
        assertThat(TitleWords.count("Handmade ceramic mug")).isEqualTo(3);
        assertThat(TitleWords.count("mug,cup;bowl-plate")).isEqualTo(4);
        assertThat(TitleWords.count("Mug - 11 oz & lid")).isEqualTo(4);
        assertThat(TitleWords.count("túi vải 2024")).isEqualTo(3);
    }

    @Test
    void anApostropheInsideAWordKeepsItTogether() {
        assertThat(TitleWords.count("men's gift")).isEqualTo(2);
        assertThat(TitleWords.count("men’s gift")).isEqualTo(2);
        assertThat(TitleWords.count("'quoted' word")).isEqualTo(2);
    }

    @Test
    void everyWordCountsEvenAnArticle() {
        assertThat(TitleWords.count("a mug for the home")).isEqualTo(5);
    }

    @Test
    void repeatsAreReportedIgnoringCaseWithTheFirstFormAndInOrderOfFirstAppearance() {
        List<TitleWords.Repeat> repeats = TitleWords.repeated("Beta alpha BETA gamma alpha beta", 1, List.of());

        assertThat(repeats).containsExactly(new TitleWords.Repeat("Beta", 3), new TitleWords.Repeat("alpha", 2));
    }

    @Test
    void aWordAtTheMaximumIsNotARepeat() {
        assertThat(TitleWords.repeated("mug mug", 2, List.of())).isEmpty();
        assertThat(TitleWords.repeated("mug mug mug", 2, List.of())).containsExactly(new TitleWords.Repeat("mug", 3));
    }

    @Test
    void exemptWordsAreNotCountedWhateverTheirCase() {
        assertThat(TitleWords.repeated("The mug and THE cup and the bowl", 1, List.of("the", "AND"))).isEmpty();
    }
}
