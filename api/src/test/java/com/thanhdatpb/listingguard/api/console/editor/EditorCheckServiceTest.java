package com.thanhdatpb.listingguard.api.console.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.check.CheckStatus;
import com.thanhdatpb.listingguard.api.check.FindingSource;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.listing.EtsyNormalizer;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.core.finding.FixAction;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every state the editor can end in, reduced from the results of the two checks (no Spring, no database): the readiness and
 * the trademark panels always exist together, a missing score is never a number, and the trademark disclaimer is always there.
 */
class EditorCheckServiceTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final Instant NOVEMBER_15 = Instant.parse("2026-11-15T10:00:00Z");
    private static final Instant DECEMBER_1 = Instant.parse("2026-12-01T00:00:00Z");

    private CheckService checks;
    private TrademarkCheckService trademarks;
    private JdbcTemplate jdbc;
    private TrademarkRuleset ruleset;
    private EditorCheckService service;
    private final ObjectNode listing = JsonNodeFactory.instance.objectNode().put("title", "Zorvex Mug");

    @BeforeEach
    void setUp() throws IOException {
        checks = mock(CheckService.class);
        trademarks = mock(TrademarkCheckService.class);
        jdbc = mock(JdbcTemplate.class);
        ruleset = TrademarkRulesetLoader.parse(
                new ClassPathResource("rulesets/trademark-risk-2026-11.yaml").getContentAsString(StandardCharsets.UTF_8));
        TrademarkRulesets rulesets = mock(TrademarkRulesets.class);
        when(rulesets.ruleset()).thenReturn(ruleset);
        NormalizerRegistry normalizers = mock(NormalizerRegistry.class);
        when(normalizers.get(anyString())).thenReturn(new EtsyNormalizer());
        service = new EditorCheckService(checks, trademarks, rulesets, normalizers, jdbc);
    }

    private static CheckResult.FindingView finding(String ruleId, Severity severity, String field, String value,
                                                   CheckResult.FixView fix) {
        return new CheckResult.FindingView(ruleId, FindingSource.RULE, severity, field, value, "message of " + ruleId,
                "suggestion of " + ruleId, fix);
    }

    private static CheckResult rules(CheckResult.ReadinessView readiness, CheckResult.FindingView... findings) {
        List<CheckResult.FindingView> list = List.of(findings);
        return new CheckResult(UUID.randomUUID(), CheckStatus.COMPLETED, "ETSY", "etsy-2026-11", "etsy-2026-11", "hash", false,
                NOVEMBER_15, new CheckResult.Summary(0, 0, 0), list, true, readiness);
    }

    private static CheckResult.ReadinessView scored(int score, boolean ready) {
        return new CheckResult.ReadinessView(score, ready, "SCORED", "readiness-2026-11");
    }

    private TrademarkCheckResult trademark(Completeness completeness, RiskLevel risk,
                                           List<TrademarkCheckResult.FindingView> findings) {
        return new TrademarkCheckResult(UUID.randomUUID(), "ETSY", "trademark-risk-2026-11", "catalog", "analysis", false,
                completeness, risk, completeness == Completeness.COMPLETE ? List.of() : List.of("catalog_fuzzy"),
                ruleset.disclaimer(), NOVEMBER_15, NOVEMBER_15.plusSeconds(3600), findings);
    }

    private static TrademarkCheckResult.FindingView mark(RiskLevel risk, String field, int start, int end) {
        return new TrademarkCheckResult.FindingView("catalog_exact", risk, field, start, end, "Zorvex", "Zorvex",
                MatchType.EXACT, 1.0, false, List.of(), List.of("S-001"), "possible reference", "review it");
    }

    private EditorResult run() {
        return service.check(CLIENT, EditorMarketplace.ETSY, listing);
    }

    @Test
    void aHighScoreAndAHighTrademarkRiskAreBothThere() {
        when(checks.checkDetailed(any(), any(CheckRequest.class))).thenReturn(rules(scored(100, true)));
        when(trademarks.check(any(), any(CheckRequest.class))).thenReturn(trademark(Completeness.COMPLETE, RiskLevel.HIGH,
                List.of(mark(RiskLevel.HIGH, "title", 0, 6))));

        EditorResult result = run();

        assertThat(result.readiness().kind()).isEqualTo(EditorResult.ReadinessKind.READY);
        assertThat(result.readiness().big()).isEqualTo("100");
        assertThat(result.trademark().kind()).isEqualTo(EditorResult.TrademarkKind.RESULT);
        assertThat(result.trademark().big()).isEqualTo("HIGH");
        assertThat(result.trademark().riskLevel()).isEqualTo("HIGH");
        assertThat(result.trademark().disclaimer()).isEqualTo(ruleset.disclaimer());
        assertThat(result.trademark().groups()).hasSize(1);
        EditorResult.TrademarkItem item = result.trademark().groups().get(0).items().get(0);
        assertThat(item.evidence().match()).isEqualTo("Zorvex");
        assertThat(item.evidence().after()).isEqualTo(" Mug");
    }

    @Test
    void anIncompleteRuleRunHasNoScoreAtAll() {
        when(checks.checkDetailed(any(), any(CheckRequest.class)))
                .thenReturn(rules(new CheckResult.ReadinessView(null, false, "INCOMPLETE", "readiness-2026-11"),
                        finding("title_length", Severity.ERROR, "title", "x", null)));
        when(trademarks.check(any(), any(CheckRequest.class)))
                .thenReturn(trademark(Completeness.COMPLETE, RiskLevel.LOW, List.of()));

        EditorResult.Readiness readiness = run().readiness();

        assertThat(readiness.kind()).isEqualTo(EditorResult.ReadinessKind.INCOMPLETE);
        assertThat(readiness.score()).isNull();
        assertThat(readiness.big()).isEqualTo("Not scored");
        assertThat(readiness.big()).doesNotContainPattern("\\d");
    }

    @Test
    void aListingWithAnErrorIsNotReady() {
        when(checks.checkDetailed(any(), any(CheckRequest.class))).thenReturn(rules(scored(40, false)));
        when(trademarks.check(any(), any(CheckRequest.class)))
                .thenReturn(trademark(Completeness.COMPLETE, RiskLevel.LOW, List.of()));

        EditorResult.Readiness readiness = run().readiness();

        assertThat(readiness.kind()).isEqualTo(EditorResult.ReadinessKind.NOT_READY);
        assertThat(readiness.score()).isEqualTo(40);
        assertThat(readiness.headline()).isEqualTo("Not ready");
    }

    @Test
    void anIncompleteTrademarkCheckWithNoEstablishedLevelIsUnknownNeverLow() {
        when(checks.checkDetailed(any(), any(CheckRequest.class))).thenReturn(rules(scored(100, true)));
        when(trademarks.check(any(), any(CheckRequest.class)))
                .thenReturn(trademark(Completeness.INCOMPLETE, RiskLevel.UNKNOWN, List.of()));

        EditorResult.Trademark trademark = run().trademark();

        assertThat(trademark.kind()).isEqualTo(EditorResult.TrademarkKind.INCOMPLETE);
        assertThat(trademark.riskLevel()).isNull();
        assertThat(trademark.big()).isEqualTo("Unknown");
        assertThat(trademark.detail()).contains("never read this as a low risk");
        assertThat(trademark.disclaimer()).isEqualTo(ruleset.disclaimer());
    }

    @Test
    void anIncompleteTrademarkCheckKeepsTheHighestLevelItEstablished() {
        when(checks.checkDetailed(any(), any(CheckRequest.class))).thenReturn(rules(scored(100, true)));
        when(trademarks.check(any(), any(CheckRequest.class))).thenReturn(trademark(Completeness.INCOMPLETE, RiskLevel.HIGH,
                List.of(mark(RiskLevel.HIGH, "title", 0, 6))));

        EditorResult.Trademark trademark = run().trademark();

        assertThat(trademark.kind()).isEqualTo(EditorResult.TrademarkKind.INCOMPLETE);
        assertThat(trademark.big()).isEqualTo("HIGH");
        assertThat(trademark.groups()).hasSize(1);
    }

    @Test
    void aCatalogThatIsNotLoadedIsSaidAndTheRuleResultsStay() {
        when(checks.checkDetailed(any(), any(CheckRequest.class)))
                .thenReturn(rules(scored(95, true), finding("banned_words", Severity.WARNING, "title", "replica", null)));
        when(trademarks.check(any(), any(CheckRequest.class))).thenThrow(new TrademarkUnavailableException("no catalog"));

        EditorResult result = run();

        assertThat(result.trademark().kind()).isEqualTo(EditorResult.TrademarkKind.UNAVAILABLE);
        assertThat(result.trademark().headline()).isEqualTo("Trademark check unavailable");
        assertThat(result.trademark().big()).isEqualTo("Unavailable");
        assertThat(result.trademark().disclaimer()).isEqualTo(ruleset.disclaimer());
        assertThat(result.rulesRan()).isTrue();
        assertThat(result.readiness().big()).isEqualTo("95");
        assertThat(result.ruleGroups()).hasSize(1);
    }

    @Test
    void aPlanWithoutTheTrademarkPackGetsANoteAndAnUpgradeHintFromTheTableOfPlans() {
        when(checks.checkDetailed(any(), any(CheckRequest.class))).thenReturn(rules(scored(100, true)));
        when(trademarks.check(any(), any(CheckRequest.class)))
                .thenThrow(new PlanNotAllowedException("FREE", Pack.TRADEMARK_RISK));
        when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.<Class<String>>any(), any(Object[].class)))
                .thenReturn(List.of("PRO", "SELLER"));

        EditorResult.Trademark trademark = run().trademark();

        assertThat(trademark.kind()).isEqualTo(EditorResult.TrademarkKind.NOT_IN_PLAN);
        assertThat(trademark.detail()).contains("FREE").contains("does not include the trademark check");
        assertThat(trademark.hint()).contains("PRO, SELLER");
        assertThat(trademark.big()).isEqualTo("Not checked");
        assertThat(trademark.disclaimer()).isEqualTo(ruleset.disclaimer());
    }

    @Test
    void aUsedUpQuotaSaysWhenItResetsInBothPanelsAndStillShowsBothPanels() {
        QuotaExceededException quota = new QuotaExceededException("SELLER", 1000, 1000, DECEMBER_1);
        when(checks.checkDetailed(any(), any(CheckRequest.class))).thenThrow(quota);
        when(trademarks.check(any(), any(CheckRequest.class))).thenThrow(quota);

        EditorResult result = run();

        assertThat(result.rulesRan()).isFalse();
        assertThat(result.ruleGroups()).isEmpty();
        assertThat(result.readiness().kind()).isEqualTo(EditorResult.ReadinessKind.NOT_RUN);
        assertThat(result.readiness().score()).isNull();
        assertThat(result.readiness().detail()).contains("2026-12-01").contains("1000 of 1000");
        assertThat(result.trademark().kind()).isEqualTo(EditorResult.TrademarkKind.QUOTA_EXCEEDED);
        assertThat(result.trademark().detail()).contains("2026-12-01");
        assertThat(result.trademark().disclaimer()).isEqualTo(ruleset.disclaimer());
    }

    @Test
    void aUsedUpRulesQuotaDoesNotHideAnAnswerThatIsAlreadyStoredForTheTrademarkCheck() {
        when(checks.checkDetailed(any(), any(CheckRequest.class)))
                .thenThrow(new QuotaExceededException("SELLER", 1000, 1000, DECEMBER_1));
        when(trademarks.check(any(), any(CheckRequest.class))).thenReturn(trademark(Completeness.COMPLETE, RiskLevel.LOW,
                List.of()));

        EditorResult result = run();

        assertThat(result.readiness().kind()).isEqualTo(EditorResult.ReadinessKind.NOT_RUN);
        assertThat(result.trademark().kind()).isEqualTo(EditorResult.TrademarkKind.RESULT);
        assertThat(result.trademark().big()).isEqualTo("LOW");
    }

    @Test
    void aPlanWithoutTheListingRulesIsSaidInTheReadinessPanel() {
        when(checks.checkDetailed(any(), any(CheckRequest.class)))
                .thenThrow(new PlanNotAllowedException("CUSTOM", Pack.LISTING_RULES));
        when(trademarks.check(any(), any(CheckRequest.class)))
                .thenThrow(new TrademarkUnavailableException("no catalog"));

        EditorResult result = run();

        assertThat(result.readiness().kind()).isEqualTo(EditorResult.ReadinessKind.NOT_RUN);
        assertThat(result.readiness().detail()).contains("CUSTOM").contains("does not include the listing rules");
        assertThat(result.trademark().kind()).isEqualTo(EditorResult.TrademarkKind.UNAVAILABLE);
    }

    @Test
    void findingsAreGroupedBySeverityWorstFirstAndCarryTheirFixAndEvidence() {
        CheckResult.FixView fix = new CheckResult.FixView(FixAction.REPLACE, "cut it", "short title");
        when(checks.checkDetailed(any(), any(CheckRequest.class))).thenReturn(rules(scored(50, false),
                finding("banned_words", Severity.WARNING, "title", "replica", null),
                finding("title_length", Severity.ERROR, "title", "a very long title", fix),
                finding("category_not_covered", Severity.INFO, null, null, null),
                finding("tag_length", Severity.ERROR, "tags[0]", "too long tag", null)));
        when(trademarks.check(any(), any(CheckRequest.class)))
                .thenReturn(trademark(Completeness.COMPLETE, RiskLevel.LOW, List.of()));

        List<EditorResult.Group<EditorResult.RuleItem>> groups = run().ruleGroups();

        assertThat(groups).extracting(EditorResult.Group::label).containsExactly("Errors", "Warnings", "Information");
        assertThat(groups.get(0).items()).extracting(EditorResult.RuleItem::ruleId).containsExactly("title_length", "tag_length");
        EditorResult.RuleItem withFix = groups.get(0).items().get(0);
        assertThat(withFix.fix().action()).isEqualTo("REPLACE");
        assertThat(withFix.fix().proposedValue()).isEqualTo("short title");
        assertThat(withFix.evidence().match()).isEqualTo("a very long title");
        assertThat(groups.get(2).items().get(0).evidence()).isNull();
    }

    @Test
    void aRequiredFieldsFindingIsFlaggedSoTheOtherFieldsOpen() {
        when(checks.checkDetailed(any(), any(CheckRequest.class)))
                .thenReturn(rules(scored(80, false), finding("required_fields", Severity.ERROR, "price", null, null)));
        when(trademarks.check(any(), any(CheckRequest.class)))
                .thenReturn(trademark(Completeness.COMPLETE, RiskLevel.LOW, List.of()));

        assertThat(run().requiredFieldsFinding()).isTrue();

        when(checks.checkDetailed(any(), any(CheckRequest.class))).thenReturn(rules(scored(100, true)));
        assertThat(run().requiredFieldsFinding()).isFalse();
    }

    @Test
    void trademarkFindingsAreGroupedByRiskHighestFirst() {
        when(checks.checkDetailed(any(), any(CheckRequest.class))).thenReturn(rules(scored(100, true)));
        when(trademarks.check(any(), any(CheckRequest.class))).thenReturn(trademark(Completeness.COMPLETE, RiskLevel.HIGH,
                List.of(mark(RiskLevel.LOW, "title", 0, 6), mark(RiskLevel.HIGH, "title", 0, 6),
                        mark(RiskLevel.MEDIUM, "title", 0, 6))));

        List<EditorResult.Group<EditorResult.TrademarkItem>> groups = run().trademark().groups();

        assertThat(groups).extracting(EditorResult.Group::label).containsExactly("High risk", "Medium risk", "Low risk");
    }
}
