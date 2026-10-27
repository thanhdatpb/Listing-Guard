package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class DuplicateTagsRuleTest {

    private final DuplicateTagsRule rule = new DuplicateTagsRule();

    private List<Finding> run(String... tags) {
        return rule.evaluate(listing("Mug", List.of(tags)), CONFIG);
    }

    @Test
    void distinctTagsAreValid() {
        assertThat(run("mug", "ceramic", "gift")).isEmpty();
    }

    @Test
    void noTagsIsValid() {
        assertThat(run()).isEmpty();
    }

    @Test
    void repeatIsAWarningNotAnError() {
        List<Finding> findings = run("mug", "ceramic", "mug");

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("duplicate_tags");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("tags[2]");
        assertThat(finding.message()).isEqualTo("Tag 'mug' trùng với tag ở vị trí 1.");
    }

    @Test
    void comparisonIgnoresCase() {
        assertThat(run("Mug", "mug", "MUG")).extracting(Finding::field).containsExactly("tags[1]", "tags[2]");
    }

    @Test
    void firstOccurrenceIsNeverFlagged() {
        assertThat(run("a", "a")).extracting(Finding::field).doesNotContain("tags[0]");
    }
}
