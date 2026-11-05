package com.thanhdatpb.listingguard.ruleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.FixAction;
import com.thanhdatpb.listingguard.core.finding.FixSuggestion;
import org.junit.jupiter.api.Test;

class FixesTest {

    @Test
    void textThatFitsHasNoCut() {
        assertThat(Fixes.cutAtWordBoundary("Handmade mug", 12)).isEmpty();
        assertThat(Fixes.cutAtWordBoundary("Handmade mug", 20)).isEmpty();
        assertThat(Fixes.cutAtWordBoundary("Handmade mug", 0)).isEmpty();
    }

    @Test
    void cutsAfterTheLastWordThatFits() {
        assertThat(Fixes.cutAtWordBoundary("alpha beta gamma delta", 12)).contains("alpha beta");
    }

    @Test
    void keepsEverythingBeforeTheLimitWhenTheLimitFallsOnASpace() {
        assertThat(Fixes.cutAtWordBoundary("alpha beta gamma", 10)).contains("alpha beta");
    }

    @Test
    void oneLongWordHasNoCut() {
        assertThat(Fixes.cutAtWordBoundary("supercalifragilistic", 10)).isEmpty();
        assertThat(Fixes.cutAtWordBoundary("supercalifragilistic mug", 10)).isEmpty();
    }

    @Test
    void textWithoutSpacesHasNoCut() {
        assertThat(Fixes.cutAtWordBoundary("中文标题没有空格", 3)).isEmpty();
    }

    @Test
    void doesNotLeaveADanglingSeparator() {
        assertThat(Fixes.cutAtWordBoundary("alpha - beta gamma", 8)).contains("alpha");
        assertThat(Fixes.cutAtWordBoundary("alpha, beta gamma", 10)).contains("alpha");
        assertThat(Fixes.cutAtWordBoundary("alpha & beta gamma", 8)).contains("alpha");
    }

    @Test
    void aCutThatLeavesNothingHasNoCut() {
        assertThat(Fixes.cutAtWordBoundary("- - - - - - - -", 4)).isEmpty();
    }

    @Test
    void aCutInsideABracketHasNoCut() {
        assertThat(Fixes.cutAtWordBoundary("mug (set of two) blue", 12)).isEmpty();
        assertThat(Fixes.cutAtWordBoundary("mug [set of two] blue", 12)).isEmpty();
    }

    @Test
    void aCompleteBracketIsKept() {
        assertThat(Fixes.cutAtWordBoundary("mug (set) blue green", 12)).contains("mug (set)");
    }

    @Test
    void countsCodePointsAndNeverSplitsASurrogatePair() {
        String face = "😀";
        String text = face.repeat(3) + " " + face.repeat(3);

        String cut = Fixes.cutAtWordBoundary(text, 5).orElseThrow();

        assertThat(cut).isEqualTo(face.repeat(3));
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(3);
    }

    @Test
    void theCutIsAlwaysAPrefixWithinTheLimitAndEndsOnAWord() {
        String text = "The quick brown fox (jumps) over - the lazy dog, again & again";
        int checked = 0;
        for (int max = 1; max < text.length(); max++) {
            var cut = Fixes.cutAtWordBoundary(text, max);
            if (cut.isEmpty()) {
                continue;
            }
            checked++;
            assertThat(text).startsWith(cut.get());
            assertThat(cut.get().codePointCount(0, cut.get().length())).isLessThanOrEqualTo(max);
            assertThat(cut.get()).isNotBlank().doesNotEndWith(" ").doesNotEndWith("-").doesNotEndWith(",");
        }
        assertThat(checked).as("limits that gave a cut").isGreaterThan(10);
    }

    @Test
    void shortenDescribesTheCutWithTheNumbers() {
        FixSuggestion fix = Fixes.shorten("tag", "alpha beta gamma delta", 12).orElseThrow();

        assertThat(fix.action()).isEqualTo(FixAction.REPLACE);
        assertThat(fix.proposedValue()).isEqualTo("alpha beta");
        assertThat(fix.description()).isEqualTo(
                "Cắt tag tại ranh giới từ còn 10 ký tự (tối đa 12); phần cuối bị bỏ: 'gamma delta'.");
    }

    @Test
    void shortenPreviewsALongDroppedTail() {
        FixSuggestion fix = Fixes.shorten("tiêu đề", "alpha " + "beta ".repeat(20).strip(), 8).orElseThrow();

        assertThat(fix.description()).contains("...'").contains("còn 5 ký tự");
    }

    @Test
    void shortenHasNothingToSayWhenThereIsNoCut() {
        assertThat(Fixes.shorten("tag", "supercalifragilistic", 10)).isEmpty();
        assertThat(Fixes.shorten("tag", "short", 10)).isEmpty();
    }

    @Test
    void removeRepeatCitesTheFirstPosition() {
        FixSuggestion fix = Fixes.removeRepeat("tag", "Mug", 2);

        assertThat(fix.action()).isEqualTo(FixAction.REMOVE);
        assertThat(fix.proposedValue()).isNull();
        assertThat(fix.description()).isEqualTo("Xoá tag 'Mug' lặp lại; bản đầu tiên đã có ở vị trí 2.");
    }
}
