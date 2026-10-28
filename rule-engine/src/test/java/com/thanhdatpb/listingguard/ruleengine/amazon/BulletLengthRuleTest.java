package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.withBullets;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rule works when called directly. In the real ruleset it is switched off ({@code enabled: false}), which is
 * tested in AmazonRulesetTest: the engine does not run it.
 */
class BulletLengthRuleTest {

    private final BulletLengthRule rule = new BulletLengthRule();
    private final int max = limit("bullet_length", "max_length");

    private List<Finding> run(String... bullets) {
        return rule.evaluate(withBullets(List.of(bullets)), CONFIG);
    }

    @Test
    void bulletsWithinTheLimitAreValid() {
        assertThat(run("Short", "x".repeat(max))).isEmpty();
    }

    @Test
    void aBulletOneOverTheLimitIsReportedAtItsPosition() {
        List<Finding> findings = run("Short", "x".repeat(max + 1), "Also short");

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("bullet_length");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("bullet_points[1]");
        assertThat(finding.value()).isEqualTo("x".repeat(max + 1));
        assertThat(finding.message()).isEqualTo("Bullet point '" + "x".repeat(25) + "...' dài " + (max + 1) + " ký tự, tối đa " + max + ".");
        assertThat(finding.suggestion()).isEqualTo("Rút ngắn bullet point này ít nhất 1 ký tự.");
    }

    @Test
    void everyLongBulletGetsItsOwnFinding() {
        List<Finding> findings = run("x".repeat(max + 5), "ok", "y".repeat(max + 10));

        assertThat(findings).extracting(Finding::field).containsExactly("bullet_points[0]", "bullet_points[2]");
        assertThat(findings.get(0).suggestion()).contains("5 ký tự");
        assertThat(findings.get(1).suggestion()).contains("10 ký tự");
    }

    @Test
    void supplementaryCharactersCountOnce() {
        assertThat(run("😀".repeat(max))).isEmpty();
        assertThat(run("😀".repeat(max + 1))).hasSize(1);
    }

    @Test
    void noBulletsHasNoFindings() {
        assertThat(rule.evaluate(withBullets(List.of()), CONFIG)).isEmpty();
    }
}
