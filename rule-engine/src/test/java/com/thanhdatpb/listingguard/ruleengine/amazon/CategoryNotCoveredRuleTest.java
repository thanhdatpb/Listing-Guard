package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.withProductType;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryNotCoveredRuleTest {

    private static final RulesetConfig WITH_CATEGORIES = RulesetLoader.loadClasspath("rulesets/amazon-test-categories.yaml");

    private final CategoryNotCoveredRule rule = new CategoryNotCoveredRule();

    @Test
    void aRulesetWithoutAnyOverrideNeverReportsIt() {
        assertThat(CONFIG.categories()).isEmpty();

        assertThat(rule.evaluate(withProductType("LUGGAGE"), CONFIG)).isEmpty();
        assertThat(rule.evaluate(withProductType("anything"), CONFIG)).isEmpty();
        assertThat(rule.evaluate(withProductType(null), CONFIG)).isEmpty();
    }

    @Test
    void theRealRulesetCoversNoProductTypeYet() {
        assertThat(CONFIG.categories()).isEmpty();
        assertThat(CONFIG.coversCategory("LUGGAGE")).isFalse();
    }

    @Test
    void aProductTypeWithoutOverridesGetsOneInfoFindingWhenTheRulesetHasOverrides() {
        List<Finding> findings = rule.evaluate(withProductType("LUGGAGE"), WITH_CATEGORIES);

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("category_not_covered");
        assertThat(finding.severity()).isEqualTo(Severity.INFO);
        assertThat(finding.field()).isEqualTo("product_type");
        assertThat(finding.value()).isEqualTo("LUGGAGE");
        assertThat(finding.message()).isEqualTo("Chưa có luật riêng cho product type 'LUGGAGE'; đang áp dụng luật chung.");
        assertThat(finding.suggestion()).startsWith("Không cần sửa listing.");
        assertThat(finding.rulesetVersion()).isEqualTo(WITH_CATEGORIES.version());
    }

    @Test
    void theValueIsTheProductTypeAsWritten() {
        assertThat(rule.evaluate(withProductType("luggage"), WITH_CATEGORIES)).extracting(Finding::value).containsExactly("luggage");
    }

    @Test
    void noProductTypeIsLeftToRequiredFields() {
        assertThat(rule.evaluate(withProductType(null), WITH_CATEGORIES)).isEmpty();
    }

    @Test
    void aProductTypeWithAnOverrideIsCoveredIgnoringCase() {
        assertThat(rule.evaluate(withProductType("TESTBAG"), WITH_CATEGORIES)).isEmpty();
        assertThat(rule.evaluate(withProductType("testbag"), WITH_CATEGORIES)).isEmpty();
        assertThat(rule.evaluate(withProductType(" TestMug "), WITH_CATEGORIES)).isEmpty();
    }

    @Test
    void aProductTypeWithoutOverridesIsNotCoveredEvenWhenOthersAre() {
        assertThat(rule.evaluate(withProductType("LUGGAGE"), WITH_CATEGORIES)).hasSize(1);
    }
}
