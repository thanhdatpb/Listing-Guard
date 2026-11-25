package com.thanhdatpb.listingguard.api.trademark.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.llm.LlmCache;
import com.thanhdatpb.listingguard.api.llm.LlmClient;
import com.thanhdatpb.listingguard.api.llm.LlmProperties;
import com.thanhdatpb.listingguard.api.llm.LlmUsageStore;
import com.thanhdatpb.listingguard.api.trademark.judge.JudgeEvidence.TsdrState;
import com.thanhdatpb.listingguard.api.trademark.judge.JudgeEvidence.VerifiedState;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import com.thanhdatpb.listingguard.trademark.AdjustmentReason;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.Evidence;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskAdjustment;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals;
import com.thanhdatpb.listingguard.trademark.catalog.InMemoryTrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The evidence pack of the trademark judge (ADR-051) on a stub provider: what the model is shown, what is left {@code unknown}, what
 * the finding keeps when it is lowered, and what stays exactly as ADR-036 had it. No network and no database.
 */
class TrademarkJudgeEvidenceTest {

    private static final String DESCRIPTIVE = "{\"verdict\":\"likely_descriptive\",\"reason\":\"Dodger là tên màu của áo.\"}";
    private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final class Stub implements LlmProvider {
        final List<LlmRequest> requests = new ArrayList<>();
        String answer = DESCRIPTIVE;

        @Override
        public String modelId() {
            return "stub:model-1";
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests.add(request);
            return new LlmResponse(answer, 50, 10, "stop");
        }
    }

    private static final class Memory implements LlmUsageStore {
        final Map<String, Long> used = new HashMap<>();

        @Override
        public boolean reserve(String providerId, LocalDate day, int limit) {
            return true;
        }

        @Override
        public void addTokens(String providerId, LocalDate day, long inputTokens, long outputTokens) {
        }

        @Override
        public Map<String, Long> requestsOn(LocalDate day) {
            return used;
        }
    }

    /** Counts its look-ups, so a test can prove none was made. */
    private static final class Sources implements JudgeEvidenceGatherer.TsdrSource, JudgeEvidenceGatherer.ReviewSource {
        final AtomicInteger lookups = new AtomicInteger();
        TsdrState tsdr;
        VerifiedState verified = VerifiedState.NOT_KNOWN;
        String digest = "";

        @Override
        public Optional<TsdrState> latest(List<String> sourceIds) {
            lookups.incrementAndGet();
            return Optional.ofNullable(tsdr);
        }

        @Override
        public VerifiedState nearest(UUID clientId, String marketplace, String ruleId, String fieldKind, String mark) {
            lookups.incrementAndGet();
            return clientId.equals(CLIENT) ? verified : VerifiedState.NOT_KNOWN;
        }

        @Override
        public String digest(UUID clientId) {
            return clientId.equals(CLIENT) ? digest : "";
        }
    }

    private static final DistinctivenessSignals SIGNALS = new DistinctivenessSignals(1, 12L, 1,
            DistinctivenessSignals.MarkKind.STANDARD_CHARACTER, true, DistinctivenessSignals.Tier.ORDINARY, null);
    private static final InMemoryTrademarkCatalog CATALOG = new InMemoryTrademarkCatalog("cat-1", List.of(
            new CatalogMark("dodger", "Dodger", List.of(25), MarkStatus.REGISTERED, false, List.of("uspto:90000001"), SIGNALS)));

    private static final TrademarkCheckInput INPUT = new TrademarkCheckInput("Dodger blue sweatshirt", List.of("gift"), List.of(), "",
            null, null, null, null);

    private static LlmProperties properties(boolean enabled, String version) {
        return new LlmProperties(enabled, List.of(), new LlmProperties.Cache(100, Duration.ofHours(1)),
                new LlmProperties.Judge(enabled, 8, 60, 600, Duration.ofSeconds(5), version));
    }

    private static LlmClient client(Stub stub, boolean listingTextAllowed) {
        Clock clock = Clock.systemUTC();
        return new LlmClient(List.of(new LlmClient.Endpoint("stub", stub, 100, 100, listingTextAllowed, false)), new Memory(),
                new LlmCache(100, Duration.ofHours(1), clock), clock);
    }

    private static TrademarkContextJudge judge(Stub stub, String version, JudgeEvidenceGatherer gatherer, boolean listingTextAllowed) {
        return new TrademarkContextJudge(client(stub, listingTextAllowed), properties(true, version), gatherer);
    }

    private static TrademarkResult result(RiskLevel level, String ruleId) {
        Evidence evidence = new Evidence("title", 0, 6, "Dodger", "dodger", "Dodger", MatchType.EXACT, 1.0, "cat-1", ruleId, false,
                List.of(), List.of("uspto:90000001"));
        TrademarkFinding finding = new TrademarkFinding(ruleId, level, "message", "suggestion", evidence);
        return new TrademarkResult("hash", "ruleset", "cat-1", level, Completeness.COMPLETE, List.of(finding), List.of(), "disclaimer");
    }

    private static JudgeContext context(Set<Integer> productClasses) {
        return new JudgeContext(CLIENT, "ETSY", productClasses);
    }

    @Test
    void theDefaultPromptVersionIsTheOldOneAndGathersNothing() {
        Stub stub = new Stub();
        Sources sources = new Sources();
        sources.verified = new VerifiedState(VerifiedState.Kind.CONFIRMED, 3);
        TrademarkContextJudge judge = judge(stub, JudgePrompt.DEFAULT_VERSION, new JudgeEvidenceGatherer(sources, sources), true);
        TrademarkResult before = result(RiskLevel.MEDIUM, "catalog_exact");

        TrademarkResult after = judge.judge(INPUT, before, CATALOG, context(Set.of(21)));

        assertThat(stub.requests).singleElement().satisfies(request -> {
            assertThat(request.system()).isEqualTo(JudgePrompt.load().system());
            assertThat(request.user()).doesNotContain("Evidence").doesNotContain("verified_case").doesNotContain("tsdr_status");
            assertThat(request.user()).startsWith("Matched text (the program put it between [[ and ]] in the sentence): Dodger\n"
                    + "Nice classes of the matched mark: 25\nField of the listing: title\n<<<DATA\n");
        });
        assertThat(sources.lookups).as("no evidence is gathered for the old prompt").hasValue(0);
        assertThat(after.findings().getFirst().risk()).isEqualTo(RiskLevel.LOW);
        RiskAdjustment adjustment = after.findings().getFirst().evidence().riskAdjustments().getLast();
        assertThat(adjustment.reason()).isEqualTo(AdjustmentReason.LLM_DESCRIPTIVE);
        assertThat(adjustment.judge()).as("the adjustment is exactly the one of ADR-036").isNull();
        assertThat(judge.fingerprintComponents(context(null)).get(TrademarkContextJudge.COMPONENT))
                .isEqualTo(JudgePrompt.load().identity() + ";models=stub:model-1;max=8;snippet=60");
    }

    @Test
    void theEvidencePromptShowsTheFactsAfterTheFixedTextAndKeepsThemWithTheDemotion() {
        Stub stub = new Stub();
        Sources sources = new Sources();
        sources.tsdr = new TsdrState("LIVE", 12);
        sources.verified = new VerifiedState(VerifiedState.Kind.CONFIRMED, 45);
        TrademarkContextJudge judge = judge(stub, JudgePrompt.EVIDENCE_VERSION, new JudgeEvidenceGatherer(sources, sources), true);

        TrademarkResult after = judge.judge(INPUT, result(RiskLevel.MEDIUM, "catalog_exact"), CATALOG, context(Set.of(21)));

        assertThat(stub.requests).singleElement().satisfies(request -> {
            assertThat(request.system()).contains("EVIDENCE").contains("never guess it").contains("never decides");
            assertThat(request.user()).startsWith("Evidence computed by the program (\"unknown\" = not available):\n"
                    + "- distinctiveness_tier: ORDINARY\n- same_mark_count: 12\n- same_mark_class_count: 1\n"
                    + "- mark_kind: STANDARD_CHARACTER\n- dictionary_word: true\n- word_count: 1\n- product_class_overlap: no\n"
                    + "- tsdr_status: LIVE, checked 12 days ago\n- verified_case: confirmed, 45 days ago\n"
                    + "Matched text (the program put it between [[ and ]] in the sentence): Dodger\n");
            assertThat(request.user().indexOf("<<<DATA")).isGreaterThan(request.user().indexOf("verified_case"));
        });
        RiskAdjustment adjustment = after.findings().getFirst().evidence().riskAdjustments().getLast();
        assertThat(after.findings().getFirst().risk()).isEqualTo(RiskLevel.LOW);
        assertThat(adjustment.judge().promptVersion()).isEqualTo(JudgePrompt.EVIDENCE_VERSION);
        assertThat(adjustment.judge().reason()).isEqualTo("Dodger là tên màu của áo.");
        assertThat(adjustment.judge().evidence()).containsEntry("distinctiveness_tier", "ORDINARY")
                .containsEntry("verified_case", "confirmed, 45 days ago").containsEntry("product_class_overlap", "no")
                .hasSize(9);
    }

    @Test
    void aFactTheProgramDoesNotHaveIsUnknownAndNeverGuessed() {
        Stub stub = new Stub();
        // no catalog to ask, no product type, no TSDR table and verified cases off: nothing is known
        TrademarkContextJudge judge = judge(stub, JudgePrompt.EVIDENCE_VERSION, JudgeEvidenceGatherer.none(), true);

        TrademarkResult after = judge.judge(INPUT, result(RiskLevel.MEDIUM, "catalog_exact"), null, JudgeContext.NONE);

        assertThat(stub.requests).singleElement().satisfies(request -> {
            for (String name : List.of("distinctiveness_tier", "same_mark_count", "same_mark_class_count", "mark_kind",
                    "dictionary_word", "word_count", "product_class_overlap", "tsdr_status", "verified_case")) {
                assertThat(request.user()).contains("- " + name + ": unknown\n");
            }
            assertThat(request.user()).contains("Nice classes of the matched mark: unknown");
        });
        assertThat(after.findings().getFirst().risk()).as("the verdict decides as before").isEqualTo(RiskLevel.LOW);
        assertThat(after.findings().getFirst().evidence().riskAdjustments().getLast().judge().evidence().values())
                .containsOnly("unknown");
    }

    @Test
    void theWordCountFallsBackToTheTokensOfTheMarkAndANoTierCatalogLeavesTheRestUnknown() {
        Stub stub = new Stub();
        InMemoryTrademarkCatalog noTier = new InMemoryTrademarkCatalog("cat-legacy", List.of(
                new CatalogMark("dodger blue", "Dodger Blue", List.of(25), MarkStatus.REGISTERED, false, List.of("uspto:90000002"))));
        Evidence phrase = new Evidence("title", 0, 11, "Dodger blue", "dodger blue", "Dodger Blue", MatchType.PHRASE, 1.0, "cat-legacy",
                "catalog_phrase", false, List.of(), List.of("uspto:90000002"));
        TrademarkResult legacy = new TrademarkResult("hash", "ruleset", "cat-legacy", RiskLevel.MEDIUM, Completeness.COMPLETE,
                List.of(new TrademarkFinding("catalog_phrase", RiskLevel.MEDIUM, "message", "suggestion", phrase)), List.of(),
                "disclaimer");

        judge(stub, JudgePrompt.EVIDENCE_VERSION, JudgeEvidenceGatherer.none(), true)
                .judge(INPUT, legacy, noTier, context(Set.of(25)));

        assertThat(stub.requests.getFirst().user()).contains("- distinctiveness_tier: unknown\n").contains("- same_mark_count: unknown\n")
                .contains("- word_count: 2\n").contains("- product_class_overlap: yes\n");
    }

    @Test
    void nothingIsGatheredOrSentForAFindingTheJudgeMayNotLookAtOrWithoutAProviderThatMayReadListingText() {
        Stub stub = new Stub();
        Sources sources = new Sources();
        TrademarkContextJudge allowed = judge(stub, JudgePrompt.EVIDENCE_VERSION, new JudgeEvidenceGatherer(sources, sources), true);
        TrademarkResult high = result(RiskLevel.HIGH, "catalog_exact");
        TrademarkResult watchlist = result(RiskLevel.MEDIUM, "watchlist_match");

        assertThat(allowed.judge(INPUT, high, CATALOG, context(null))).isSameAs(high);
        assertThat(allowed.judge(INPUT, watchlist, CATALOG, context(null)).findings().getFirst().risk()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(stub.requests).isEmpty();
        assertThat(sources.lookups).hasValue(0);

        TrademarkContextJudge forbidden = judge(stub, JudgePrompt.EVIDENCE_VERSION, new JudgeEvidenceGatherer(sources, sources), false);
        TrademarkResult medium = result(RiskLevel.MEDIUM, "catalog_exact");
        assertThat(forbidden.active()).isFalse();
        assertThat(forbidden.judge(INPUT, medium, CATALOG, context(null))).isSameAs(medium);
        assertThat(forbidden.fingerprintComponents(context(null))).isEmpty();
        assertThat(stub.requests).isEmpty();
        assertThat(sources.lookups).hasValue(0);
    }

    @Test
    void aVerdictThatIsNotDescriptiveChangesNothingAndRecordsNothing() {
        for (String answer : List.of("{\"verdict\":\"likely_mark_use\",\"reason\":\"Dùng như nhãn hiệu.\"}",
                "{\"verdict\":\"unsure\",\"reason\":\"\"}", "not json")) {
            Stub stub = new Stub();
            stub.answer = answer;
            TrademarkResult before = result(RiskLevel.MEDIUM, "catalog_exact");

            TrademarkResult after = judge(stub, JudgePrompt.EVIDENCE_VERSION, JudgeEvidenceGatherer.none(), true)
                    .judge(INPUT, before, CATALOG, context(null));

            assertThat(after).as(answer).isSameAs(before);
        }
    }

    @Test
    void anUnknownPromptVersionStopsTheStartWhetherOrNotTheJudgeIsOn() {
        Stub stub = new Stub();
        for (boolean enabled : new boolean[] {true, false}) {
            LlmProperties properties = properties(enabled, "trademark-judge-2099-01");
            assertThatThrownBy(() -> new TrademarkContextJudge(client(stub, true), properties))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("listing-guard.llm.judge.prompt-version").hasMessageContaining(JudgePrompt.EVIDENCE_VERSION);
        }
        assertThat(JudgePrompt.forVersion(JudgePrompt.DEFAULT_VERSION).evidence()).isFalse();
        assertThat(JudgePrompt.forVersion(JudgePrompt.EVIDENCE_VERSION).evidence()).isTrue();
        assertThat(JudgePrompt.forVersion(JudgePrompt.DEFAULT_VERSION).schema())
                .isEqualTo(JudgePrompt.forVersion(JudgePrompt.EVIDENCE_VERSION).schema());
    }

    @Test
    void theVerifiedDecisionsOfTheClientAreInTheAnalysisVersionOnlyForTheEvidencePrompt() {
        Stub stub = new Stub();
        Sources sources = new Sources();
        sources.digest = "2:1760000000";
        JudgeEvidenceGatherer gatherer = new JudgeEvidenceGatherer(sources, sources);

        String evidence = judge(stub, JudgePrompt.EVIDENCE_VERSION, gatherer, true).fingerprintComponents(context(null))
                .get(TrademarkContextJudge.COMPONENT);
        String old = judge(stub, JudgePrompt.DEFAULT_VERSION, gatherer, true).fingerprintComponents(context(null))
                .get(TrademarkContextJudge.COMPONENT);
        String other = judge(stub, JudgePrompt.EVIDENCE_VERSION, gatherer, true)
                .fingerprintComponents(new JudgeContext(UUID.randomUUID(), "ETSY", null)).get(TrademarkContextJudge.COMPONENT);
        sources.digest = "";
        String off = judge(stub, JudgePrompt.EVIDENCE_VERSION, gatherer, true).fingerprintComponents(context(null))
                .get(TrademarkContextJudge.COMPONENT);

        assertThat(evidence).startsWith(JudgePrompt.forVersion(JudgePrompt.EVIDENCE_VERSION).identity())
                .endsWith(";verified=2:1760000000");
        assertThat(old).doesNotContain("verified");
        assertThat(other).as("another client's decisions are not this client's").doesNotContain("verified");
        assertThat(off).as("verified cases off: nothing to add").doesNotContain("verified");
    }
}
