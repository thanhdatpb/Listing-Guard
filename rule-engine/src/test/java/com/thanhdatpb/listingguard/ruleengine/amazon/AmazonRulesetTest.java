package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.listing;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.valid;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The real Amazon ruleset: its content, the agreed severity policy, and a run through the engine. */
class AmazonRulesetTest {

    private final RuleEngine engine = new RuleEngine(RuleRegistry.withDefaults());

    @Test
    void loadsTheAmazonRuleset() {
        assertThat(CONFIG.marketplace()).isEqualTo("AMAZON");
        assertThat(CONFIG.version()).isEqualTo("amazon-2026-11");
        assertThat(CONFIG.checkedAt()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(CONFIG.contentHash()).matches("[0-9a-f]{64}");
        assertThat(CONFIG.rules()).containsOnlyKeys("required_fields", "title_length", "title_repeated_words",
                "title_forbidden_chars", "bullet_count", "bullet_length", "search_terms_length", "prohibited_content",
                "category_not_covered");
    }

    @Test
    void everyRegisteredAmazonRuleHasConfigurationAndTheRulesetHasNoUnknownRule() {
        engine.validate(CONFIG);
        assertThat(AmazonRules.all()).extracting(Rule::id).containsExactlyInAnyOrderElementsOf(CONFIG.rules().keySet());
    }

    @Test
    void severityFollowsTheSourceStatus() {
        // Amazon policy (ADR-006): ERROR only for verified values; secondary and unverified are WARNING.
        CONFIG.rules().values().forEach(settings -> {
            if (settings.severity() == Severity.ERROR) {
                assertThat(settings.source().status()).as(settings.ruleId()).isEqualTo(SourceStatus.VERIFIED);
            }
            if (settings.source().status() != SourceStatus.VERIFIED && settings.severity() != Severity.INFO) {
                assertThat(settings.severity()).as(settings.ruleId()).isEqualTo(Severity.WARNING);
            }
        });
    }

    @Test
    void theAgreedSeveritiesAndStatuses() {
        assertRule("required_fields", Severity.ERROR, SourceStatus.VERIFIED);
        assertRule("title_length", Severity.WARNING, SourceStatus.SECONDARY);
        assertRule("title_repeated_words", Severity.WARNING, SourceStatus.SECONDARY);
        assertRule("title_forbidden_chars", Severity.WARNING, SourceStatus.SECONDARY);
        assertRule("bullet_count", Severity.WARNING, SourceStatus.UNVERIFIED);
        assertRule("bullet_length", Severity.WARNING, SourceStatus.UNVERIFIED);
        assertRule("search_terms_length", Severity.WARNING, SourceStatus.SECONDARY);
        assertRule("prohibited_content", Severity.WARNING, SourceStatus.SECONDARY);
        assertRule("category_not_covered", Severity.INFO, SourceStatus.UNVERIFIED);
    }

    private static void assertRule(String id, Severity severity, SourceStatus status) {
        RuleSettings settings = CONFIG.rule(id);
        assertThat(settings.severity()).as(id).isEqualTo(severity);
        assertThat(settings.source().status()).as(id).isEqualTo(status);
    }

    @Test
    void everyVerifiedOrSecondarySourceHasAUrl() {
        CONFIG.rules().values().forEach(settings -> {
            if (settings.source().status() != SourceStatus.UNVERIFIED) {
                assertThat(settings.source().urls()).as(settings.ruleId()).isNotEmpty();
            }
        });
    }

    @Test
    void onlyBulletLengthIsSwitchedOff() {
        assertThat(CONFIG.rule("bullet_length").enabled()).isFalse();
        CONFIG.rules().values().stream().filter(settings -> !settings.ruleId().equals("bullet_length"))
                .forEach(settings -> assertThat(settings.enabled()).as(settings.ruleId()).isTrue());
    }

    @Test
    void noProductTypeIsOverriddenYet() {
        assertThat(CONFIG.categories()).isEmpty();
        CONFIG.rules().values().forEach(settings -> assertThat(settings.categoryOverrides()).as(settings.ruleId()).isEmpty());
    }

    @Test
    void theEngineDoesNotRunTheSwitchedOffBulletLengthRule() {
        int max = CONFIG.rule("bullet_length").intParam("max_length");
        Listing listing = AmazonTestData.withBullets(List.of("x".repeat(max * 4)));

        assertThat(new BulletLengthRule().evaluate(listing, CONFIG)).hasSize(1);
        assertThat(engine.run(listing, CONFIG)).extracting(Finding::ruleId).doesNotContain("bullet_length");
    }

    @Test
    void aValidListingOnlyGetsTheCategoryNotCoveredInfo() {
        List<Finding> findings = engine.run(valid(), CONFIG);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).ruleId()).isEqualTo("category_not_covered");
        assertThat(findings.get(0).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void aListingWithoutAProductTypeGetsTheRequiredFieldErrorAndNoCategoryInfo() {
        List<Finding> findings = engine.run(AmazonTestData.withProductType(null), CONFIG);

        assertThat(findings).extracting(Finding::ruleId).containsExactly("required_fields");
        assertThat(findings.get(0).severity()).isEqualTo(Severity.ERROR);
    }

    @Test
    void aListingWithEveryKindOfProblemIsReportedInRuleOrder() {
        int titleMax = CONFIG.rule("title_length").intParam("max_length");
        int bulletMax = CONFIG.rule("bullet_count").intParam("max_count");
        int termsMax = CONFIG.rule("search_terms_length").intParam("max_bytes");
        Listing listing = listing(
                "bag bag bag! " + "x".repeat(titleMax), "Roadster",
                Collections.nCopies(bulletMax + 1, "See www.example.com"), "Mail us: a@example.com",
                "t".repeat(termsMax + 1), "LUGGAGE");

        List<Finding> findings = engine.run(listing, CONFIG);

        assertThat(findings).extracting(Finding::ruleId).containsExactly(
                "title_length", "title_repeated_words", "title_forbidden_chars", "bullet_count",
                "search_terms_length", "prohibited_content", "prohibited_content", "prohibited_content", "prohibited_content",
                "prohibited_content", "prohibited_content", "prohibited_content", "category_not_covered");
        assertThat(findings).extracting(Finding::rulesetVersion).containsOnly(CONFIG.version());
        assertThat(findings).extracting(Finding::severity).doesNotContain(Severity.ERROR);
        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.field()).isNotBlank();
            assertThat(finding.message()).isNotBlank();
            assertThat(finding.suggestion()).isNotBlank();
        });
    }
}
