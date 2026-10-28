package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.empty;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.valid;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.withProductType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequiredFieldsRuleTest {

    private static final String ALL_FIELDS = """
            marketplace: AMAZON
            version: test-required
            checked_at: 2026-10-28
            rules:
              required_fields:
                severity: ERROR
                fields: [title, brand, description, bullet_points, search_terms, product_type, price, quantity, images]
                source:
                  status: unverified
            """;

    private final RequiredFieldsRule rule = new RequiredFieldsRule();

    @Test
    void aListingWithAProductTypeIsValid() {
        assertThat(rule.evaluate(valid(), CONFIG)).isEmpty();
    }

    @Test
    void aMissingProductTypeIsAnErrorWithNoValue() {
        List<Finding> findings = rule.evaluate(withProductType(null), CONFIG);

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("required_fields");
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.field()).isEqualTo("product_type");
        assertThat(finding.value()).isNull();
        assertThat(finding.message()).isEqualTo("Thiếu trường bắt buộc 'product_type'.");
        assertThat(finding.suggestion()).contains("product_type");
        assertThat(finding.rulesetVersion()).isEqualTo(CONFIG.version());
    }

    @Test
    void theRealRulesetRequiresOnlyTheProductType() {
        assertThat(CONFIG.rule("required_fields").stringListParam("fields")).containsExactly("product_type");
        assertThat(rule.evaluate(empty(), CONFIG)).extracting(Finding::field).containsExactly("product_type");
    }

    @Test
    void everySupportedFieldIsReportedWhenMissingInTheConfiguredOrder() {
        RulesetConfig config = RulesetLoader.parse(ALL_FIELDS);

        assertThat(rule.evaluate(empty(), config)).extracting(Finding::field).containsExactly(
                "title", "brand", "description", "bullet_points", "search_terms", "product_type", "price", "quantity", "images");
        assertThat(rule.evaluate(valid(), config)).isEmpty();
    }

    @Test
    void anUnknownFieldFailsValidation() {
        RulesetConfig config = RulesetLoader.parse(ALL_FIELDS.replace("images]", "images, colour]"));

        assertThatThrownBy(() -> rule.validate(config)).isInstanceOf(RulesetException.class).hasMessageContaining("colour");
    }

    @Test
    void aMissingFieldsKeyFailsValidation() {
        RulesetConfig config = RulesetLoader.parse(ALL_FIELDS.replace("fields:", "other:"));

        assertThatThrownBy(() -> rule.validate(config)).isInstanceOf(RulesetException.class).hasMessageContaining("fields");
    }
}
