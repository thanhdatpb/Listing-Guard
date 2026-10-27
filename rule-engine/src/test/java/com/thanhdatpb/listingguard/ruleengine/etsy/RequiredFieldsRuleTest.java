package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.empty;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequiredFieldsRuleTest {

    private final RequiredFieldsRule rule = new RequiredFieldsRule();

    @Test
    void completeListingIsValid() {
        assertThat(rule.evaluate(valid(), CONFIG)).isEmpty();
    }

    @Test
    void emptyListingMissesEveryConfiguredField() {
        List<String> required = CONFIG.rule("required_fields").stringListParam("fields");

        List<Finding> findings = rule.evaluate(empty(), CONFIG);

        assertThat(findings).extracting(Finding::field).containsExactlyElementsOf(required);
        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.ruleId()).isEqualTo("required_fields");
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        });
    }

    @Test
    void messageNamesTheMissingField() {
        Listing base = valid();
        Listing noDescription = new Listing(base.title(), null, base.tags(), base.price(), base.category(),
                base.materials(), base.images(), base.quantity(), base.whoMade(), base.whenMade());

        List<Finding> findings = rule.evaluate(noDescription, CONFIG);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).field()).isEqualTo("description");
        assertThat(findings.get(0).message()).isEqualTo("Thiếu trường bắt buộc 'description'.");
        assertThat(findings.get(0).suggestion()).contains("description");
    }

    @Test
    void imagesAreNotRequiredByTheEtsyRuleset() {
        Listing base = valid();
        Listing noImages = new Listing(base.title(), base.description(), base.tags(), base.price(), base.category(),
                base.materials(), List.of(), base.quantity(), base.whoMade(), base.whenMade());

        assertThat(rule.evaluate(noImages, CONFIG)).isEmpty();
    }

    @Test
    void unknownFieldNameInTheRulesetFailsValidation() {
        RulesetConfig broken = RulesetLoader.parse("""
                marketplace: ETSY
                version: broken
                checked_at: 2026-10-27
                rules:
                  required_fields:
                    severity: ERROR
                    fields: [title, bogus]
                    source:
                      status: unverified
                """);

        assertThatThrownBy(() -> rule.validate(broken))
                .isInstanceOf(RulesetException.class)
                .hasMessageContaining("bogus");
    }
}
