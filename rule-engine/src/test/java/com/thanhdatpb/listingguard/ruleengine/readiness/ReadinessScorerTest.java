package com.thanhdatpb.listingguard.ruleengine.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.RuleRun;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadinessScorerTest {

    private static final ReadinessConfig CONFIG = ReadinessLoader.loadClasspath("readiness/readiness-2026-11.yaml");
    private static final ReadinessScorer SCORER = new ReadinessScorer(CONFIG);
    private static final String PACK = CONFIG.packId();

    private static Finding finding(Severity severity) {
        return new Finding("r", severity, "title", null, "m", "s", "v");
    }

    private static RuleRun run(int errors, int warnings, int infos) {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < errors; i++) {
            findings.add(finding(Severity.ERROR));
        }
        for (int i = 0; i < warnings; i++) {
            findings.add(finding(Severity.WARNING));
        }
        for (int i = 0; i < infos; i++) {
            findings.add(finding(Severity.INFO));
        }
        return new RuleRun(PACK, findings, List.of());
    }

    private static int penalty(Severity severity) {
        return CONFIG.penalty(severity);
    }

    @Test
    void aListingWithNoFindingsScoresTheBaseAndIsReady() {
        ReadinessScore score = SCORER.score(run(0, 0, 0));

        assertThat(score.status()).isEqualTo(ReadinessScore.Status.SCORED);
        assertThat(score.score()).isEqualTo(CONFIG.baseScore());
        assertThat(score.ready()).isTrue();
        assertThat(score.version()).isEqualTo("readiness-2026-11");
        assertThat(score.packId()).isEqualTo("listing-rules");
    }

    @Test
    void warningsAndInfosTakePointsOffButDoNotMakeAListingNotReady() {
        ReadinessScore score = SCORER.score(run(0, 2, 3));

        assertThat(score.score()).isEqualTo(CONFIG.baseScore() - 2 * penalty(Severity.WARNING) - 3 * penalty(Severity.INFO));
        assertThat(score.ready()).isTrue();
        assertThat(score.warnings()).isEqualTo(2);
        assertThat(score.infos()).isEqualTo(3);
    }

    @Test
    void anErrorMakesTheListingNotReadyAndCapsTheScore() {
        ReadinessScore score = SCORER.score(run(1, 0, 0));

        assertThat(score.ready()).isFalse();
        assertThat(CONFIG.baseScore() - penalty(Severity.ERROR)).as("uncapped value").isGreaterThan(59);
        assertThat(score.score()).isEqualTo(Math.min(CONFIG.baseScore() - penalty(Severity.ERROR), CONFIG.notReadyMaxScore()));
        assertThat(score.score()).isEqualTo(59);
        assertThat(score.errors()).isEqualTo(1);
    }

    @Test
    void theScoreNeverGoesBelowZero() {
        assertThat(SCORER.score(run(50, 50, 50)).score()).isZero();
        assertThat(SCORER.score(run(0, 1000, 0)).score()).isZero();
        assertThat(SCORER.score(run(0, 1000, 0)).ready()).isTrue();
    }

    @Test
    void aListingThatIsNotReadyNeverScoresAboveTheCap() {
        for (int errors = 1; errors <= 12; errors++) {
            for (int warnings = 0; warnings <= 12; warnings++) {
                ReadinessScore score = SCORER.score(run(errors, warnings, 0));
                assertThat(score.ready()).isFalse();
                assertThat(score.score()).as(errors + " errors, " + warnings + " warnings").isLessThanOrEqualTo(59);
            }
        }
    }

    @Test
    void theCapAppliesEvenWhenThePenaltiesAreSmall() {
        ReadinessConfig gentle = ReadinessLoader.parse("""
                version: readiness-gentle
                checked_at: 2026-11-05
                pack_id: listing-rules
                base_score: 100
                penalties:
                  ERROR: 1
                  WARNING: 1
                  INFO: 0
                not_ready_max_score: 59
                source:
                  status: unverified
                  note: test
                """);

        assertThat(new ReadinessScorer(gentle).score(run(1, 0, 0)).score()).isEqualTo(59);
        assertThat(new ReadinessScorer(gentle).score(run(0, 1, 0)).score()).isEqualTo(99);
    }

    @Test
    void aRunInWhichARuleFailedHasNoScoreAndIsNotReady() {
        ReadinessScore score = SCORER.score(new RuleRun(PACK, List.of(), List.of("title_length")));

        assertThat(score.status()).isEqualTo(ReadinessScore.Status.INCOMPLETE);
        assertThat(score.score()).isNull();
        assertThat(score.ready()).isFalse();
        assertThat(score.failedRuleIds()).containsExactly("title_length");
    }

    @Test
    void anIncompleteRunStaysUnscoredWhateverItsFindings() {
        List<Finding> findings = List.of(finding(Severity.ERROR), finding(Severity.WARNING));

        ReadinessScore score = SCORER.score(new RuleRun(PACK, findings, List.of("tag_count")));

        assertThat(score.score()).isNull();
        assertThat(score.ready()).isFalse();
        assertThat(score.errors()).isEqualTo(1);
        assertThat(score.warnings()).isEqualTo(1);
    }

    @Test
    void aRunOfAnotherPackIsRefused() {
        assertThatThrownBy(() -> SCORER.score(new RuleRun("trademark-risk", List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("listing-rules")
                .hasMessageContaining("trademark-risk");
    }

    @Test
    void aScoreMustBeConsistentWithItsStatus() {
        assertThatThrownBy(() -> new ReadinessScore("v", PACK, ReadinessScore.Status.SCORED, null, true, 0, 0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadinessScore("v", PACK, ReadinessScore.Status.SCORED, 101, true, 0, 0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadinessScore("v", PACK, ReadinessScore.Status.INCOMPLETE, 50, false, 0, 0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadinessScore("v", PACK, ReadinessScore.Status.INCOMPLETE, null, true, 0, 0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- with the real engine and the real Etsy ruleset ---------------------------------------------------------------

    private static final RulesetConfig ETSY = RulesetLoader.loadClasspath("rulesets/etsy-2026-11.yaml");
    private static final RuleEngine ENGINE = new RuleEngine(RuleRegistry.withDefaults());

    private static Listing etsy(String title) {
        return new Listing(title, "A nice mug for tea.", List.of("mug", "ceramic"), new Money(new BigDecimal("19.99"), "USD"),
                "Home > Mugs", List.of("clay"), List.of("https://example.com/1.jpg"), 5, "i_did", "made_to_order");
    }

    @Test
    void aCleanEtsyListingIsReadyWithTheBaseScore() {
        ReadinessScore score = SCORER.score(ENGINE.runDetailed(etsy("Handmade ceramic mug"), ETSY));

        assertThat(score.ready()).isTrue();
        assertThat(score.score()).isEqualTo(100);
    }

    @Test
    void anEtsyListingWithATooLongTitleIsNotReady() {
        int max = ETSY.rule("title_length").intParam("max_length");

        ReadinessScore score = SCORER.score(ENGINE.runDetailed(etsy("alpha ".repeat(max)), ETSY));

        assertThat(score.ready()).isFalse();
        assertThat(score.score()).isLessThanOrEqualTo(59);
        assertThat(score.errors()).isEqualTo(1);
    }

    @Test
    void theScoreIgnoresAFixSuggestionAndCountsFindingsOnly() {
        int max = ETSY.rule("title_length").intParam("max_length");
        RuleRun withFix = ENGINE.runDetailed(etsy("alpha ".repeat(max)), ETSY);
        List<Finding> stripped = withFix.findings().stream().map(Finding::withoutFix).toList();

        assertThat(withFix.findings().stream().anyMatch(f -> f.fix() != null)).isTrue();
        assertThat(SCORER.score(new RuleRun(withFix.packId(), stripped, withFix.failedRuleIds())))
                .isEqualTo(SCORER.score(withFix));
    }
}
