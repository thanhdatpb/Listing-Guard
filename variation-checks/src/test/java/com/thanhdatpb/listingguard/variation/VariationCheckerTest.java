package com.thanhdatpb.listingguard.variation;

import static com.thanhdatpb.listingguard.variation.VariationFixtures.CHECKER;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.RULESET;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.etsy;
import static com.thanhdatpb.listingguard.variation.VariationFixtures.validFamily;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.variation.config.RuleIds;
import com.thanhdatpb.listingguard.variation.config.SourceStatus;
import com.thanhdatpb.listingguard.variation.rules.RuleContext;
import com.thanhdatpb.listingguard.variation.rules.VariationRule;
import com.thanhdatpb.listingguard.variation.rules.VariationRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VariationCheckerTest {

    /** A rule that cannot run. */
    private static VariationRule failing(String id, Set<String> marketplaces) {
        return new VariationRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> marketplaces() {
                return marketplaces;
            }

            @Override
            public List<VariationFinding> apply(RuleContext context) {
                throw new IllegalStateException("boom");
            }
        };
    }

    private static List<VariationRule> shipped() {
        return new ArrayList<>(VariationRules.create(RULESET));
    }

    @Test
    void aRuleThatThrowsMakesTheResultIncompleteAndTheOtherFindingsStay() {
        List<VariationRule> rules = shipped();
        rules.removeIf(rule -> rule.id().equals(RuleIds.DUPLICATE_SKU));
        rules.add(failing(RuleIds.DUPLICATE_SKU, Set.of("AMAZON", "ETSY")));
        VariationChecker checker = new VariationChecker(RULESET, rules);

        // the family has no parent, so parent_child_structure has a finding to keep
        VariationInput noParent = VariationFixtures.withParent(validFamily(), null);
        VariationResult result = checker.check(noParent);

        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(result.failedRuleIds()).containsExactly(RuleIds.DUPLICATE_SKU);
        assertThat(result.findings()).extracting(VariationFinding::kind).contains(RuleIds.PARENT_MISSING);
        assertThat(result.inputHash()).isEqualTo(CanonicalInput.of(noParent).hash());
        assertThat(result.rulesetVersion()).isEqualTo(RULESET.version());
        assertThat(result.disclaimer()).isEqualTo(RULESET.disclaimer());
    }

    @Test
    void severalRulesMayFailAndEachIsNamedOnce() {
        List<VariationRule> rules = shipped();
        rules.removeIf(rule -> rule.id().equals(RuleIds.DUPLICATE_SKU) || rule.id().equals(RuleIds.DUPLICATE_COMBINATION));
        rules.add(failing(RuleIds.DUPLICATE_SKU, Set.of("AMAZON")));
        rules.add(failing(RuleIds.DUPLICATE_COMBINATION, Set.of("AMAZON")));

        VariationResult result = new VariationChecker(RULESET, rules).check(validFamily());

        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(result.failedRuleIds()).containsExactlyInAnyOrder(RuleIds.DUPLICATE_SKU, RuleIds.DUPLICATE_COMBINATION);
    }

    @Test
    void aRuleThatIsNotDeclaredForTheMarketplaceDoesNotRunAndCannotFailIt() {
        List<VariationRule> rules = shipped();
        rules.add(failing("amazon_only_and_broken", Set.of("AMAZON")));

        VariationResult result = new VariationChecker(RULESET, rules).check(etsy(List.of("Size"), 2));

        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(result.failedRuleIds()).isEmpty();
    }

    @Test
    void aCleanFamilyIsCompleteWithNoFindingAndAZeroSummary() {
        VariationResult result = CHECKER.check(validFamily());

        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(result.findings()).isEmpty();
        assertThat(result.summary()).isEqualTo(new VariationResult.Summary(0, 0, 0));
    }

    @Test
    void theSummaryCountsFindingsBySeverity() {
        VariationFinding warning = new VariationFinding("r", "k", Severity.WARNING, SourceStatus.SECONDARY, "f", null,
                List.of(), "m", "s");
        VariationFinding info = new VariationFinding("r", "k", Severity.INFO, SourceStatus.SECONDARY, "f", null,
                List.of(), "m", "s");
        VariationResult result = new VariationResult("h", "v", Completeness.COMPLETE, List.of(warning, info, info), List.of(), "d");

        assertThat(result.summary()).isEqualTo(new VariationResult.Summary(0, 1, 2));
    }

    @Test
    void aMarketplaceNoRuleRunsForIsRefused() {
        VariationInput input = new VariationInput("EBAY", null, List.of(), List.of());

        assertThatThrownBy(() -> CHECKER.check(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EBAY");
    }

    @Test
    void theRulesRunInTheOrderOfTheRuleset() {
        assertThat(VariationRules.create(RULESET)).extracting(VariationRule::id)
                .containsExactlyElementsOf(RULESET.rules().stream().map(spec -> spec.id()).toList());
    }
}
