package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TagCountRuleTest {

    private final TagCountRule rule = new TagCountRule();
    private final int max = limit("tag_count", "max_count");

    private List<Finding> run(int tagCount) {
        List<String> tags = IntStream.range(0, tagCount).mapToObj(i -> "tag" + i).toList();
        return rule.evaluate(listing("Mug", tags), CONFIG);
    }

    @Test
    void noTagsIsValidHere() {
        assertThat(run(0)).isEmpty();
    }

    @Test
    void exactlyMaxTagsIsValid() {
        assertThat(run(max)).isEmpty();
    }

    @Test
    void oneOverMaxIsAnError() {
        List<Finding> findings = run(max + 1);

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("tag_count");
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.field()).isEqualTo("tags");
        assertThat(finding.message()).isEqualTo("Listing có " + (max + 1) + " tag, tối đa " + max + ".");
        assertThat(finding.suggestion()).contains("Bỏ bớt 1 tag");
    }
}
