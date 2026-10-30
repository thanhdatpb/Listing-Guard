package com.thanhdatpb.listingguard.rag.analysis;

import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.FIELDS;
import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.MAX_FINDINGS;
import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.MAX_TEXT;
import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.TOPICS;
import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.finding;
import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.findingsJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PolicyFindingParserTest {

    private static final String QUOTE = "If a production partner helps make";

    private final PolicyFindingParser parser = new PolicyFindingParser(TOPICS, FIELDS, MAX_FINDINGS, MAX_TEXT);

    @Test
    void readsAValidFinding() {
        ParseResult result = parser.parse(findingsJson(finding(7, QUOTE)));

        assertThat(result.proposed()).isEqualTo(1);
        assertThat(result.schemaInvalid()).isZero();
        assertThat(result.valid()).containsExactly(new ProposedFinding("policy.production_partner",
                Severity.WARNING, "description", "print partner", "Danh sach noi ro doi tac in.",
                "Ghi ro doi tac in trong mo ta.", 7L, QUOTE));
    }

    @Test
    void nullValueAndAnEmptyListAreValid() {
        Map<String, Object> omission = finding(7, QUOTE);
        omission.put("value", null);

        assertThat(parser.parse(findingsJson(omission)).valid()).hasSize(1);
        assertThat(parser.parse("{\"findings\":[]}").proposed()).isZero();
    }

    @Test
    void errorSeverityIsAcceptedHereAndLoweredByTheVerifier() {
        Map<String, Object> f = finding(7, QUOTE);
        f.put("severity", "ERROR");

        assertThat(parser.parse(findingsJson(f)).valid()).extracting(ProposedFinding::severity)
                .containsExactly(Severity.ERROR);
    }

    @Test
    void messageAndSuggestionMayHaveExactlyTheLimit() {
        Map<String, Object> f = finding(7, QUOTE);
        f.put("message", "m".repeat(MAX_TEXT));
        f.put("suggestion", "s".repeat(MAX_TEXT));

        assertThat(parser.parse(findingsJson(f)).valid()).hasSize(1);
    }

    /** Each case breaks one thing in an otherwise valid finding; the item is dropped, not repaired. */
    static Stream<Arguments> invalidItems() {
        return Stream.of(
                breaking("unknown topic", f -> f.put("ruleId", "policy.invented")),
                breaking("ruleId without the policy. prefix", f -> f.put("ruleId", "production_partner")),
                breaking("ruleId with a wrong prefix", f -> f.put("ruleId", "rule.production_partner")),
                breaking("topic in another case", f -> f.put("ruleId", "policy.PRODUCTION_PARTNER")),
                breaking("ruleId is not text", f -> f.put("ruleId", 5)),
                breaking("severity in lower case", f -> f.put("severity", "warning")),
                breaking("unknown severity", f -> f.put("severity", "CRITICAL")),
                breaking("severity missing", f -> f.remove("severity")),
                breaking("field outside the enum", f -> f.put("field", "photos")),
                breaking("field in another case", f -> f.put("field", "Title")),
                breaking("field missing", f -> f.remove("field")),
                breaking("value key missing", f -> f.remove("value")),
                breaking("value is a number", f -> f.put("value", 12)),
                breaking("value is blank", f -> f.put("value", "   ")),
                breaking("message missing", f -> f.remove("message")),
                breaking("message blank", f -> f.put("message", " ")),
                breaking("message over the limit", f -> f.put("message", "m".repeat(MAX_TEXT + 1))),
                breaking("suggestion over the limit", f -> f.put("suggestion", "s".repeat(MAX_TEXT + 1))),
                breaking("suggestion is null", f -> f.put("suggestion", null)),
                breaking("citation missing", f -> f.remove("citation")),
                breaking("citation is a string", f -> f.put("citation", "chunk 7")),
                breaking("chunkId is text", f -> f.put("citation", Map.of("chunkId", "7", "quote", QUOTE))),
                breaking("chunkId is a fraction", f -> f.put("citation", Map.of("chunkId", 7.5, "quote", QUOTE))),
                breaking("chunkId missing", f -> f.put("citation", Map.of("quote", QUOTE))),
                breaking("quote missing", f -> f.put("citation", Map.of("chunkId", 7))),
                breaking("quote is not text", f -> f.put("citation", Map.of("chunkId", 7, "quote", 1))));
    }

    private static Arguments breaking(String name, Consumer<Map<String, Object>> breakIt) {
        Map<String, Object> f = finding(7, QUOTE);
        breakIt.accept(f);
        return Arguments.of(name, f);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidItems")
    void anItemThatDoesNotMatchTheSchemaIsDroppedAndCounted(String name, Map<String, Object> broken) {
        ParseResult result = parser.parse(findingsJson(finding(7, QUOTE), broken, finding(8, QUOTE)));

        assertThat(result.proposed()).isEqualTo(3);
        assertThat(result.schemaInvalid()).isEqualTo(1);
        assertThat(result.valid()).extracting(ProposedFinding::chunkId).containsExactly(7L, 8L);
    }

    @Test
    void anItemThatIsNotAnObjectIsDropped() {
        ParseResult result = parser.parse("{\"findings\":[\"text\", 5, null, [], " + findingsItem() + "]}");

        assertThat(result.proposed()).isEqualTo(5);
        assertThat(result.valid()).hasSize(1);
    }

    @Test
    void moreFindingsThanTheLimitKeepsTheFirstOnesAndCountsTheRestAsInvalid() {
        int extra = 2;

        ParseResult result = parser.parse(findingsJson(findings(MAX_FINDINGS + extra)));

        assertThat(result.proposed()).isEqualTo(MAX_FINDINGS + extra);
        assertThat(result.valid()).hasSize(MAX_FINDINGS);
        assertThat(result.valid()).extracting(ProposedFinding::chunkId)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, MAX_FINDINGS).boxed().toList());
        assertThat(result.schemaInvalid()).isEqualTo(extra);
    }

    @Test
    void theLimitItselfIsAllowed() {
        ParseResult result = parser.parse(findingsJson(findings(MAX_FINDINGS)));

        assertThat(result.valid()).hasSize(MAX_FINDINGS);
        assertThat(result.schemaInvalid()).isZero();
    }

    @Test
    void theFindingLimitIsAParameter() {
        int tighter = MAX_FINDINGS - 3;
        PolicyFindingParser narrow = new PolicyFindingParser(TOPICS, FIELDS, tighter, MAX_TEXT);

        ParseResult result = narrow.parse(findingsJson(findings(MAX_FINDINGS)));

        assertThat(result.valid()).hasSize(tighter);
        assertThat(result.schemaInvalid()).isEqualTo(MAX_FINDINGS - tighter);
    }

    @Test
    void theTextLimitIsAParameter() {
        PolicyFindingParser narrow = new PolicyFindingParser(TOPICS, FIELDS, MAX_FINDINGS, MAX_TEXT - 1);
        Map<String, Object> f = finding(7, QUOTE);
        f.put("message", "m".repeat(MAX_TEXT));

        assertThat(parser.parse(findingsJson(f)).valid()).hasSize(1);
        assertThat(narrow.parse(findingsJson(f)).valid()).isEmpty();
    }

    @Test
    void theTextLimitCountsCodePointsNotUtf16Units() {
        Map<String, Object> f = finding(7, QUOTE);
        f.put("message", "😀".repeat(MAX_TEXT));

        assertThat(parser.parse(findingsJson(f)).valid()).hasSize(1);
    }

    private static Object[] findings(int count) {
        List<Object> items = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            items.add(finding(i, QUOTE));
        }
        return items.toArray();
    }

    @ParameterizedTest(name = "maxFindings={0}, maxTextLength={1}")
    @CsvSource({"0,1", "-1,1", "1,0", "1,-1", "0,0"})
    void aLimitThatIsNotPositiveIsRefused(int maxFindings, int maxTextLength) {
        assertThatThrownBy(() -> new PolicyFindingParser(TOPICS, FIELDS, maxFindings, maxTextLength))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "",
            "   ",
            "not json at all",
            "{\"findings\": [",
            "{\"findings\":[]} and then some prose",
            "{\"findings\":[]}{\"findings\":[]}",
            "```json\n{\"findings\":[]}\n```",
            "[]",
            "[{\"ruleId\":\"policy.other\"}]",
            "\"findings\"",
            "null",
            "{}",
            "{\"findings\": null}",
            "{\"findings\": {}}",
            "{\"findings\": \"none\"}",
            "{\"result\": []}",
            "{\"findings\":[],\"findings\":[]}"
    })
    void anAnswerThatIsNotTheFindingsObjectThrows(String text) {
        assertThatThrownBy(() -> parser.parse(text)).isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void nullTextThrows() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void topicsAndFieldsComeFromTheCaller() {
        PolicyFindingParser narrow = new PolicyFindingParser(Set.of("other"), Set.of("title"), MAX_FINDINGS, MAX_TEXT);
        Map<String, Object> f = finding(7, QUOTE);

        assertThat(narrow.parse(findingsJson(f)).valid()).isEmpty();

        f.put("ruleId", "policy.other");
        f.put("field", "title");
        assertThat(narrow.parse(findingsJson(f)).valid()).hasSize(1);
        assertThatThrownBy(() -> new PolicyFindingParser(Set.of(), FIELDS, MAX_FINDINGS, MAX_TEXT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyFindingParser(TOPICS, Set.of(), MAX_FINDINGS, MAX_TEXT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String findingsItem() {
        String all = findingsJson(finding(7, QUOTE));
        return all.substring(all.indexOf('[') + 1, all.lastIndexOf(']'));
    }
}
