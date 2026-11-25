package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.empty;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.RuleRun;
import com.thanhdatpb.listingguard.ruleengine.readiness.ReadinessLoader;
import com.thanhdatpb.listingguard.ruleengine.readiness.ReadinessScore;
import com.thanhdatpb.listingguard.ruleengine.readiness.ReadinessScorer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Checking only the fields a Sheet has (ADR-052): a presence rule does not report a field the data never had, still reports one that was
 * supplied and is empty, and every other rule and the full run are unchanged.
 */
class RuleEngineContentScopeTest {

    private static final Set<String> SHEET_FIELDS = Set.of("title", "description", "tags");

    private final RuleEngine engine = new RuleEngine(RuleRegistry.withDefaults());
    private final ReadinessScorer scorer = new ReadinessScorer(ReadinessLoader.loadClasspath("readiness/readiness-2026-11.yaml"));

    /** What a Sheet without category, price, quantity, who_made and when_made gives the engine. */
    private static Listing sheetRow(String title, List<String> tags) {
        return new Listing(title, "A nice mug for tea.", tags, null, null, List.of(), List.of(), null, null, null);
    }

    private static List<String> required() {
        return CONFIG.rule("required_fields").stringListParam("fields");
    }

    private static List<String> notInTheSheet() {
        return required().stream().filter(field -> !SHEET_FIELDS.contains(field)).toList();
    }

    @Test
    void aFieldTheSheetNeverHadIsNotReportedAsMissingAndTheRunNamesWhatWasLeftOut() {
        RuleRun run = engine.runDetailed(sheetRow("Handmade ceramic mug", List.of("mug", "ceramic")), CONFIG, SHEET_FIELDS);

        assertThat(run.findings()).extracting(Finding::ruleId).doesNotContain("required_fields");
        assertThat(run.skippedRuleIds()).containsExactly("required_fields");
        assertThat(run.skippedFields()).containsExactlyElementsOf(notInTheSheet());
        assertThat(notInTheSheet()).isNotEmpty();
        assertThat(run.complete()).isTrue();
        assertThat(run.partial()).isTrue();
    }

    @Test
    void aFieldTheSheetHasButLeftEmptyIsStillReported() {
        Listing noTitle = sheetRow(null, List.of("mug"));

        RuleRun run = engine.runDetailed(noTitle, CONFIG, SHEET_FIELDS);

        assertThat(run.findings()).filteredOn(finding -> finding.ruleId().equals("required_fields"))
                .extracting(Finding::field).containsExactly("title");
        assertThat(run.skippedFields()).doesNotContain("title");
    }

    @Test
    void everyOtherRuleStillRunsOnTheFieldsTheSheetHas() {
        String longTitle = "x".repeat(limit("title_length", "max_length") + 1);
        String longTag = "t".repeat(limit("tag_length", "max_length") + 1);

        RuleRun run = engine.runDetailed(sheetRow(longTitle, List.of(longTag)), CONFIG, SHEET_FIELDS);

        assertThat(run.findings()).extracting(Finding::ruleId).contains("title_length", "tag_length");
        assertThat(run.findings()).extracting(Finding::ruleId).doesNotContain("required_fields");
    }

    @Test
    void theFindingsOfARunWithEveryFieldSuppliedAreExactlyTheFullRun() {
        for (Listing sample : List.of(empty(), listing("Handmade ceramic mug", List.of("mug")), sheetRow("A title", List.of("a")))) {
            RuleRun full = engine.runDetailed(sample, CONFIG);

            RuleRun everyField = engine.runDetailed(sample, CONFIG, Set.copyOf(required()));
            RuleRun nullSet = engine.runDetailed(sample, CONFIG, null);

            assertThat(everyField.findings()).isEqualTo(full.findings());
            assertThat(nullSet).isEqualTo(full);
            assertThat(everyField.partial()).isFalse();
            assertThat(nullSet.skippedFields()).isEmpty();
        }
    }

    @Test
    void theFullRunStillReportsEveryMissingField() {
        List<String> fields = engine.runDetailed(empty(), CONFIG).findings().stream()
                .filter(finding -> finding.ruleId().equals("required_fields")).map(Finding::field).toList();

        assertThat(fields).containsExactlyElementsOf(required());
    }

    @Test
    void skippedFieldsAnswersBeforeTheRunWithWhatTheRunLeavesOut() {
        Listing row = sheetRow("A title", List.of("a"));

        assertThat(engine.skippedFields(row, CONFIG, SHEET_FIELDS)).isEqualTo(engine.runDetailed(row, CONFIG, SHEET_FIELDS).skippedFields());
        assertThat(engine.skippedFields(row, CONFIG, null)).isEmpty();
        assertThat(engine.skippedFields(row, CONFIG, Set.copyOf(required()))).isEmpty();
    }

    @Test
    void aPartialRunIsNeverReadyAndHasNoScoreEvenWithoutAFinding() {
        RuleRun run = engine.runDetailed(sheetRow("Handmade ceramic mug", List.of("mug", "ceramic")), CONFIG, SHEET_FIELDS);

        ReadinessScore score = scorer.score(run);

        assertThat(run.findings()).isEmpty();
        assertThat(score.status()).isEqualTo(ReadinessScore.Status.PARTIAL);
        assertThat(score.score()).isNull();
        assertThat(score.ready()).isFalse();
    }

    @Test
    void theSameListingInTheFullRunIsNotReadyEitherBecauseTheFieldsAreMissing() {
        RuleRun run = engine.runDetailed(sheetRow("Handmade ceramic mug", List.of("mug", "ceramic")), CONFIG);

        ReadinessScore score = scorer.score(run);

        assertThat(score.status()).isEqualTo(ReadinessScore.Status.SCORED);
        assertThat(score.ready()).isFalse();
        assertThat(score.errors()).isEqualTo(notInTheSheet().size());
    }
}
