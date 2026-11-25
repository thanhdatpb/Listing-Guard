package com.thanhdatpb.listingguard.api.trademark.judge;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.llm.LlmCache;
import com.thanhdatpb.listingguard.api.llm.LlmClient;
import com.thanhdatpb.listingguard.api.llm.LlmProperties;
import com.thanhdatpb.listingguard.api.llm.LlmUsageStore;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import com.thanhdatpb.listingguard.trademark.AdjustmentReason;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.Evidence;
import com.thanhdatpb.listingguard.trademark.LlmJudgement;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The judge on a stub provider: no network, no database. */
class TrademarkContextJudgeTest {

    private static final String DESCRIPTION = "Soft cotton, secret buyer list: Nguyen Van A, 12 Hang Bac street, Hanoi. "
            + "Pair it with jeans. " + "x".repeat(400);
    private static final String DESCRIPTIVE = "{\"verdict\":\"likely_descriptive\",\"reason\":\"Dodger blue là tên màu của áo.\"}";

    /** Answers from a function of the prompt; remembers every request. */
    private static final class Stub implements LlmProvider {
        final List<LlmRequest> requests = new ArrayList<>();
        Function<LlmRequest, String> answer = request -> DESCRIPTIVE;
        RuntimeException failure;

        @Override
        public String modelId() {
            return "stub:model-1";
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return new LlmResponse(answer.apply(request), 50, 10, "stop");
        }
    }

    private static final class Memory implements LlmUsageStore {
        final Map<String, Long> used = new HashMap<>();

        @Override
        public boolean reserve(String providerId, LocalDate day, int limit) {
            long now = used.getOrDefault(providerId, 0L);
            if (now >= limit) {
                return false;
            }
            used.put(providerId, now + 1);
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

    private static LlmProperties properties(boolean enabled, boolean judge) {
        return new LlmProperties(enabled, List.of(), new LlmProperties.Cache(100, Duration.ofHours(1)),
                new LlmProperties.Judge(judge, 8, 30, 600, Duration.ofSeconds(5), JudgePrompt.DEFAULT_VERSION));
    }

    private static TrademarkContextJudge judge(Stub stub, boolean listingTextAllowed, int perDay, boolean enabled) {
        Clock clock = Clock.systemUTC();
        LlmClient client = new LlmClient(List.of(new LlmClient.Endpoint("stub", stub, 100, perDay, listingTextAllowed, false)),
                new Memory(), new LlmCache(100, Duration.ofHours(1), clock), clock);
        return new TrademarkContextJudge(client, properties(enabled, enabled));
    }

    private static final TrademarkCheckInput INPUT = new TrademarkCheckInput("Dodger blue sweatshirt", List.of("gift"), List.of(), DESCRIPTION,
            null, null, null, null);

    private static TrademarkFinding finding(String ruleId, RiskLevel risk, String field, int start, int end) {
        Evidence evidence = new Evidence(field, start, end, "Dodger blue", "dodger blue", "Dodger Blue", MatchType.PHRASE, 1.0,
                "cat-1", ruleId, false, List.of(), List.of("sn1"));
        return new TrademarkFinding(ruleId, risk, "message", "suggestion", evidence);
    }

    private static TrademarkResult result(TrademarkFinding... findings) {
        List<TrademarkFinding> list = List.of(findings);
        RiskLevel level = RiskLevel.highest(list.stream().map(TrademarkFinding::risk).toList());
        return new TrademarkResult("hash", "ruleset", "cat-1", level, Completeness.COMPLETE, list, List.of(), "disclaimer");
    }

    @Test
    void offReturnsTheSameResultAndAddsNothingToTheAnalysisVersion() {
        Stub stub = new Stub();
        TrademarkContextJudge off = judge(stub, true, 10, false);
        TrademarkResult before = result(finding("catalog_phrase", RiskLevel.MEDIUM, "title", 0, 11));

        assertThat(off.active()).isFalse();
        assertThat(off.judge(INPUT, before, null)).isSameAs(before);
        assertThat(off.fingerprintComponents()).isEmpty();
        assertThat(TrademarkContextJudge.off().fingerprintComponents()).isEmpty();
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void aDescriptiveVerdictLowersTheFindingAndSendsOnlyTheSentenceAroundTheMatch() {
        Stub stub = new Stub();
        TrademarkResult before = result(finding("catalog_phrase", RiskLevel.MEDIUM, "title", 0, 11));

        TrademarkResult after = judge(stub, true, 10, true).judge(INPUT, before, null);

        assertThat(after.findings()).hasSize(1);
        assertThat(after.findings().get(0).risk()).isEqualTo(RiskLevel.LOW);
        assertThat(after.findings().get(0).evidence().riskAdjustments().get(0).reason()).isEqualTo(AdjustmentReason.LLM_DESCRIPTIVE);
        assertThat(after.findings().get(0).evidence().riskAdjustments().get(0).note()).contains("Dodger blue là tên màu của áo.");
        assertThat(after.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(after.completeness()).isEqualTo(Completeness.COMPLETE);

        assertThat(stub.requests).hasSize(1);
        String sent = stub.requests.get(0).user();
        assertThat(sent).contains("[[Dodger blue]]", "Dodger Blue", "Field of the listing: title", "<<<DATA", "DATA>>>");
        assertThat(sent).doesNotContain("Nguyen", "Hang Bac", "gift", "x".repeat(10));
    }

    @Test
    void anAnswerThatIsNotTheSchemaIsUnsureAndChangesNothing() {
        for (String bad : new String[] {"", "yes", "```json\n" + DESCRIPTIVE + "\n```", "{\"verdict\":\"likely_descriptive\"}",
                "{\"verdict\":\"descriptive\",\"reason\":\"x\"}", "{\"verdict\":\"likely_descriptive\",\"reason\":\"\"}",
                "{\"verdict\":\"likely_descriptive\",\"reason\":\"x\",\"extra\":1}", "{\"verdict\":1,\"reason\":\"x\"}",
                "{\"verdict\":\"likely_descriptive\",\"reason\":\"" + "y".repeat(201) + "\"}", "[1]", "{not json}"}) {
            Stub stub = new Stub();
            stub.answer = request -> bad;
            TrademarkResult before = result(finding("catalog_phrase", RiskLevel.MEDIUM, "title", 0, 11));

            TrademarkResult after = judge(stub, true, 10, true).judge(INPUT, before, null);

            assertThat(after).as(bad).isSameAs(before);
        }
    }

    @Test
    void markUseAndUnsureChangeNothing() {
        for (String verdict : new String[] {"likely_mark_use", "unsure"}) {
            Stub stub = new Stub();
            stub.answer = request -> "{\"verdict\":\"" + verdict + "\",\"reason\":\"không rõ\"}";
            TrademarkResult before = result(finding("catalog_phrase", RiskLevel.MEDIUM, "title", 0, 11));

            assertThat(judge(stub, true, 10, true).judge(INPUT, before, null)).isSameAs(before);
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void onlyMediumCatalogFindingsAreSent() {
        Stub stub = new Stub();
        TrademarkResult before = result(finding("catalog_phrase", RiskLevel.HIGH, "title", 0, 11),
                finding("watchlist_match", RiskLevel.MEDIUM, "title", 0, 11),
                finding("geo_sport_combination", RiskLevel.MEDIUM, "title", 0, 11),
                finding("catalog_fuzzy", RiskLevel.LOW, "title", 0, 11));

        assertThat(judge(stub, true, 10, true).judge(INPUT, before, null)).isSameAs(before);
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void whenTheDailyLimitIsUsedUpTheStepIsSkippedAndTheResultIsIncomplete() {
        Stub stub = new Stub();
        TrademarkContextJudge judge = judge(stub, true, 1, true);
        TrademarkResult before = result(finding("catalog_phrase", RiskLevel.MEDIUM, "title", 0, 11));
        judge.judge(INPUT, before, null);

        TrademarkResult second = judge.judge(new TrademarkCheckInput("Dodger blue mug", List.of(), List.of(), "", null, null, null, null), before, null);

        assertThat(second.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(second.skippedRuleIds()).containsExactly(LlmJudgement.SKIPPED_ID);
        assertThat(second.findings()).isEqualTo(before.findings());
        assertThat(second.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(stub.requests).as("the second check sent nothing").hasSize(1);
    }

    @Test
    void aFailingProviderSkipsTheStepAndKeepsTheRules() {
        Stub stub = new Stub();
        stub.failure = new LlmException(LlmException.Kind.TIMEOUT, "t");
        TrademarkResult before = result(finding("catalog_phrase", RiskLevel.MEDIUM, "title", 0, 11));

        TrademarkResult after = judge(stub, true, 10, true).judge(INPUT, before, null);

        assertThat(after.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(after.findings()).isEqualTo(before.findings());
    }

    @Test
    void withoutPermissionForListingTextNothingIsSentAndTheResultIsUntouched() {
        Stub stub = new Stub();
        TrademarkContextJudge judge = judge(stub, false, 10, true);
        TrademarkResult before = result(finding("catalog_phrase", RiskLevel.MEDIUM, "title", 0, 11));

        assertThat(judge.active()).isFalse();
        assertThat(judge.judge(INPUT, before, null)).isSameAs(before);
        assertThat(judge.fingerprintComponents()).isEmpty();
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void personalizationTextNeverReachesAProviderThatMayNotSeeIt() {
        Stub stub = new Stub();
        TrademarkCheckInput input = new TrademarkCheckInput("Dodger blue sweatshirt", List.of(), List.of(), DESCRIPTION, null, null, null, null);
        TrademarkResult before = result(finding("catalog_phrase", RiskLevel.MEDIUM, "personalization_instructions", 0, 11));

        TrademarkResult after = judge(stub, true, 10, true).judge(input, before, null);

        assertThat(stub.requests).isEmpty();
        assertThat(after.findings()).isEqualTo(before.findings());
    }

    @Test
    void theAnalysisVersionNamesThePromptAndTheModelAndTheSettings() {
        TrademarkContextJudge judge = judge(new Stub(), true, 10, true);

        String component = judge.fingerprintComponents().get(TrademarkContextJudge.COMPONENT);

        assertThat(component).startsWith("trademark-judge-2026-11:").contains("models=stub:model-1", "max=8", "snippet=30");
        assertThat(component).doesNotContain("\n");
    }

    @Test
    void theContextIsCutOnBothSidesAndMarksTheMatch() {
        assertThat(TrademarkContextJudge.context("0123456789", 4, 6, 100)).isEqualTo("0123[[45]]6789");
        assertThat(TrademarkContextJudge.context("0123456789", 4, 6, 2)).isEqualTo("...23[[45]]67...");
    }

    @Test
    void thePromptFileHasAVersionAndFillsEveryPlaceholder() {
        JudgePrompt prompt = JudgePrompt.load();

        assertThat(prompt.version()).isEqualTo("trademark-judge-2026-11");
        assertThat(prompt.identity()).matches("trademark-judge-2026-11:[0-9a-f]{12}");
        String rendered = prompt.render("Mark {text}", "25", "title", "a {mark} b");
        assertThat(rendered).contains("Mark {text}", "Nice classes of the matched mark: 25", "a {mark} b").doesNotContain("{classes}");
    }
}
