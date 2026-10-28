package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.withBullets;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class BulletCountRuleTest {

    private final BulletCountRule rule = new BulletCountRule();
    private final int max = limit("bullet_count", "max_count");

    private List<Finding> run(int count) {
        return rule.evaluate(withBullets(Collections.nCopies(count, "A bullet point")), CONFIG);
    }

    @Test
    void noBulletsIsValidHere() {
        assertThat(run(0)).isEmpty();
    }

    @Test
    void exactlyTheMaximumIsValid() {
        assertThat(run(max)).isEmpty();
    }

    @Test
    void oneOverTheMaximumIsReported() {
        List<Finding> findings = run(max + 1);

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("bullet_count");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("bullet_points");
        assertThat(finding.value()).isEqualTo(String.valueOf(max + 1));
        assertThat(finding.message()).isEqualTo("Listing có " + (max + 1) + " bullet point, tối đa " + max + ".");
        assertThat(finding.suggestion()).isEqualTo("Bỏ bớt 1 bullet point, giữ lại các ý quan trọng nhất.");
    }

    @Test
    void suggestionSaysHowManyToRemove() {
        assertThat(run(max + 4).get(0).suggestion()).contains("Bỏ bớt 4 bullet point");
    }
}
