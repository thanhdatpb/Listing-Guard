package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class TagLengthRuleTest {

    private final TagLengthRule rule = new TagLengthRule();
    private final int max = limit("tag_length", "max_length");

    private List<Finding> run(String... tags) {
        return rule.evaluate(listing("Mug", List.of(tags)), CONFIG);
    }

    @Test
    void shortTagsAreValid() {
        assertThat(run("mug", "ceramic")).isEmpty();
    }

    @Test
    void tagOfExactlyMaxLengthIsValid() {
        assertThat(run("x".repeat(max))).isEmpty();
    }

    @Test
    void tagOneOverMaxIsAnError() {
        List<Finding> findings = run("x".repeat(max + 1));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(findings.get(0).ruleId()).isEqualTo("tag_length");
    }

    @Test
    void messageNamesTheTagItsLengthAndTheLimit() {
        int length = max + 4;
        String tag = "x".repeat(length);

        Finding finding = run("ok", tag).get(0);

        assertThat(finding.field()).isEqualTo("tags[1]");
        assertThat(finding.message()).isEqualTo("Tag '" + tag + "' dài " + length + " ký tự, tối đa " + max + ".");
        assertThat(finding.suggestion()).contains("4 ký tự");
    }

    @Test
    void veryLongTagsArePreviewedInTheMessage() {
        Finding finding = run("y".repeat(60)).get(0);

        assertThat(finding.message()).startsWith("Tag 'yyyyyyyyyyyyyyyyyyyyyyyyy...'").contains("dài 60 ký tự");
    }

    @Test
    void reportsEveryOffendingTag() {
        assertThat(run("x".repeat(max + 1), "fine", "z".repeat(max + 2)))
                .extracting(Finding::field).containsExactly("tags[0]", "tags[2]");
    }
}
