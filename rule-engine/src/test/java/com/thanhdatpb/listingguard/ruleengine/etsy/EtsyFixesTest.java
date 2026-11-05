package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.FixAction;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The candidate fixes the Etsy rules propose (ADR-014, A3). The engine verifies them; see RuleEngineFixTest. */
class EtsyFixesTest {

    private final int titleMax = limit("title_length", "max_length");
    private final int tagMax = limit("tag_length", "max_length");

    private static String words(int minLength) {
        StringBuilder text = new StringBuilder("alpha1");
        for (int i = 2; text.length() <= minLength; i++) {
            text.append(" alpha").append(i);
        }
        return text.toString();
    }

    @Test
    void aLongTitleGetsAReplaceFixWithinTheLimit() {
        String title = words(titleMax);

        Finding finding = new TitleLengthRule().evaluate(listing(title, List.of()), CONFIG).get(0);

        assertThat(finding.fix().action()).isEqualTo(FixAction.REPLACE);
        String proposed = finding.fix().proposedValue();
        assertThat(title).startsWith(proposed);
        assertThat(proposed.codePointCount(0, proposed.length())).isLessThanOrEqualTo(titleMax);
        assertThat(finding.fix().description()).contains("tối đa " + titleMax);
        assertThat(finding.value()).isEqualTo(title);
    }

    @Test
    void theOldSuggestionSentenceIsUnchanged() {
        Finding finding = new TitleLengthRule().evaluate(listing(words(titleMax), List.of()), CONFIG).get(0);

        assertThat(finding.suggestion()).startsWith("Rút ngắn tiêu đề ít nhất ").endsWith(" ký tự.");
    }

    @Test
    void aTitleThatIsOneLongWordGetsNoFix() {
        Finding finding = new TitleLengthRule().evaluate(listing("x".repeat(titleMax + 1), List.of()), CONFIG).get(0);

        assertThat(finding.fix()).isNull();
    }

    @Test
    void aLongTagGetsAReplaceFixOnItsOwnPosition() {
        String tooLong = "mug " + "x".repeat(tagMax);

        List<Finding> findings = new TagLengthRule().evaluate(listing("Handmade mug", List.of("ceramic", tooLong)), CONFIG);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).field()).isEqualTo("tags[1]");
        assertThat(findings.get(0).fix().action()).isEqualTo(FixAction.REPLACE);
        assertThat(findings.get(0).fix().proposedValue()).isEqualTo("mug");
    }

    @Test
    void aLongTagThatIsOneWordGetsNoFix() {
        List<Finding> findings = new TagLengthRule().evaluate(
                listing("Handmade mug", List.of("x".repeat(tagMax + 1))), CONFIG);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).fix()).isNull();
    }

    @Test
    void aTagAtExactlyTheLimitHasNoFinding() {
        assertThat(new TagLengthRule().evaluate(listing("Handmade mug", List.of("x".repeat(tagMax))), CONFIG)).isEmpty();
    }

    @Test
    void aRepeatedTagGetsARemoveFixThatNamesTheFirstPosition() {
        List<Finding> findings = new DuplicateTagsRule().evaluate(
                listing("Handmade mug", List.of("ceramic", "mug", "Mug")), CONFIG);

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.field()).isEqualTo("tags[2]");
        assertThat(finding.fix().action()).isEqualTo(FixAction.REMOVE);
        assertThat(finding.fix().proposedValue()).isNull();
        assertThat(finding.fix().description()).contains("'Mug'").endsWith("ở vị trí 2.");
    }
}
