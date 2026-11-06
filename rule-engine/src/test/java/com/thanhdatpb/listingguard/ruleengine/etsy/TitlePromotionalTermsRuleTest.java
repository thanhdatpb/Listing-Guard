package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class TitlePromotionalTermsRuleTest {

    private final TitlePromotionalTermsRule rule = new TitlePromotionalTermsRule();
    private final List<String> terms = CONFIG.rule("title_promotional_terms").stringListParam("terms");

    private List<Finding> run(String title) {
        return rule.evaluate(listing(title, List.of("mug")), CONFIG);
    }

    private static RulesetConfig withTerms(String termsYaml) {
        return RulesetLoader.parse("""
                marketplace: ETSY
                version: t
                checked_at: 2026-11-06
                rules:
                  title_promotional_terms:
                    severity: WARNING
                    %s
                    source:
                      status: unverified
                """.formatted(termsYaml));
    }

    @Test
    void everyConfiguredTermIsFoundInATitleThatHasIt() {
        for (String term : terms) {
            List<Finding> findings = run("Ceramic mug " + term + " gift");

            assertThat(findings).as("term '%s'", term).hasSize(1);
            assertThat(findings.get(0).value()).isEqualToIgnoringCase(term);
        }
    }

    @Test
    void aTitleWithFreeShippingIsAWarningThatNamesThePhrase() {
        assertThat(terms).as("the ruleset lists free shipping").contains("free shipping");

        List<Finding> findings = run("Handmade ceramic mug Free Shipping");

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("title_promotional_terms");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("title");
        assertThat(finding.value()).isEqualTo("Free Shipping");
        assertThat(finding.message()).isEqualTo("Tiêu đề có cụm từ về giá, vận chuyển hoặc khuyến mãi: 'Free Shipping'.");
        assertThat(finding.suggestion()).startsWith("Cân nhắc bỏ cụm từ này khỏi tiêu đề");
        assertThat(finding.fix()).as("no rule proposes replacement words").isNull();
    }

    @Test
    void aPhraseMatchesAcrossAnyAmountOfWhitespaceAndIgnoringCase() {
        assertThat(run("Mug FREE   shipping")).hasSize(1);
        assertThat(run("Mug free\tshipping")).hasSize(1);
    }

    @Test
    void aTermIsAWholeWordOrPhrase() {
        assertThat(run("Wholesale mug")).isEmpty();
        assertThat(run("Salesman mug")).isEmpty();
        assertThat(run("Discounted mug")).isEmpty();
        assertThat(run("Free shippings mug")).isEmpty();
        assertThat(run("Shipping mug free")).isEmpty();
    }

    @Test
    void aTermThatStartsWithASymbolNeedsNoBoundaryOnThatSide() {
        assertThat(terms).contains("% off");

        assertThat(run("Mug 20% off")).extracting(Finding::value).containsExactly("% off");
        assertThat(run("Mug 20 % off")).hasSize(1);
        assertThat(run("Mug 20% offer")).as("still a whole word on the right").isEmpty();
    }

    @Test
    void eachTermFoundGetsOneFindingEvenWhenItIsRepeated() {
        assertThat(run("Sale mug on sale, sale")).hasSize(1);
        assertThat(run("Sale mug free shipping")).extracting(Finding::value).containsExactlyInAnyOrder("Sale", "free shipping");
    }

    @Test
    void aTitleWithNoPromotionalTermOrNoTitleHasNoFinding() {
        assertThat(run("Handmade ceramic mug")).isEmpty();
        assertThat(rule.evaluate(listing(null, List.of("mug")), CONFIG)).isEmpty();
    }

    @Test
    void theTermsComeFromTheConfigAndAreValidated() {
        RulesetConfig config = withTerms("terms: [bargain]");

        assertThat(rule.evaluate(listing("A bargain mug, on sale", List.of()), config)).extracting(Finding::value)
                .containsExactly("bargain");
        assertThat(rule.evaluate(listing("Free shipping", List.of()), config)).isEmpty();
        assertThatThrownBy(() -> rule.validate(withTerms("terms: []"))).isInstanceOf(RulesetException.class)
                .hasMessageContaining("terms");
        assertThatThrownBy(() -> rule.validate(withTerms("terms: ['  ']"))).isInstanceOf(RulesetException.class)
                .hasMessageContaining("terms");
        assertThatThrownBy(() -> rule.validate(withTerms("other: 1"))).isInstanceOf(RulesetException.class)
                .hasMessageContaining("terms");
    }

    @Test
    void theRulesetRatesItAsAWarningWithASecondarySource() {
        assertThat(CONFIG.rule("title_promotional_terms").severity()).isEqualTo(Severity.WARNING);
        assertThat(CONFIG.rule("title_promotional_terms").source().status()).isEqualTo(SourceStatus.SECONDARY);
    }
}
