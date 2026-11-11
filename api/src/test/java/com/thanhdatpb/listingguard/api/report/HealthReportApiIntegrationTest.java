package com.thanhdatpb.listingguard.api.report;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.quota;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.setUsed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.docs.ApiExamples;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.report.HealthReportTestData.Readiness;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code GET /api/v1/reports/summary} (ADR-018) against a real database: only the caller's own numbers, zeros for an empty
 * period, the period rules, the ranked lists, ISO week boundaries, retention, readiness, trademark levels, what never leaves
 * the database, and that it is free and writes nothing. It shares its Spring context with the plan tests (plans are on), so a
 * report is shown to cost nothing where something could be charged.
 */
@SpringBootTest(properties = "listing-guard.plans.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class HealthReportApiIntegrationTest {

    private static final String PATH = "/api/v1/reports/summary";
    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final String WEEK_FROM = "2026-11-02";
    private static final String WEEK_TO = "2026-11-08";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    MutableClock clock;

    @Autowired
    ActiveRulesets rulesets;

    HealthReportTestData data;

    @BeforeEach
    void atTheStartOfTheReportWeek() {
        clock.set(NOW);
        data = new HealthReportTestData(jdbc);
    }

    private TestClient client() {
        return newClient(provisioning, clients, PlanCode.PRO);
    }

    private static Instant t(String instant) {
        return Instant.parse(instant);
    }

    private static Instant after30Days(Instant instant) {
        return instant.plus(Duration.ofDays(30));
    }

    private ResultActions get(TestClient client, String from, String to, String marketplace) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(PATH).header(HEADER, client.key());
        if (from != null) {
            request.param("from", from);
        }
        if (to != null) {
            request.param("to", to);
        }
        if (marketplace != null) {
            request.param("marketplace", marketplace);
        }
        return mvc.perform(request);
    }

    private String body(TestClient client, String from, String to, String marketplace) throws Exception {
        return get(client, from, to, marketplace).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode report(TestClient client, String from, String to, String marketplace) throws Exception {
        return mapper.readTree(body(client, from, to, marketplace));
    }

    private JsonNode week(TestClient client) throws Exception {
        return report(client, WEEK_FROM, WEEK_TO, null);
    }

    private static List<String> names(JsonNode array, String field) {
        List<String> names = new ArrayList<>();
        array.forEach(item -> names.add(item.get(field).asText()));
        return names;
    }

    // ---- isolation, empty, free

    @Test
    void aClientSeesOnlyItsOwnNumbers() throws Exception {
        TestClient a = client();
        TestClient b = client();
        UUID a1 = data.listing(a.id(), "ETSY", t("2026-11-03T10:00:00Z"));
        data.finding(a1, "ERROR", "rule_only_a");
        for (int i = 0; i < 3; i++) {
            UUID b1 = data.listing(b.id(), "ETSY", t("2026-11-03T10:00:00Z"));
            data.finding(b1, "ERROR", "rule_only_b");
        }
        UUID tb = data.trademark(b.id(), "ETSY", t("2026-11-03T10:00:00Z"), after30Days(t("2026-11-03T10:00:00Z")), "COMPLETE", "HIGH");
        data.trademarkFinding(tb, 0, "catalog_exact", "HIGH", "MARK_ONLY_B");
        data.variation(b.id(), "ETSY", t("2026-11-03T10:00:00Z"), after30Days(t("2026-11-03T10:00:00Z")), "COMPLETE");
        data.screening(b.id(), "ETSY", t("2026-11-03T10:00:00Z"), after30Days(t("2026-11-03T10:00:00Z")), "COMPLETE", "COMPLETE", "LOW");

        String forA = body(a, WEEK_FROM, WEEK_TO, null);
        String forB = body(b, WEEK_FROM, WEEK_TO, null);

        JsonNode reportA = mapper.readTree(forA);
        assertThat(reportA.at("/packs/listingRules/checks").asLong()).isEqualTo(1);
        assertThat(reportA.at("/packs/trademarkRisk/checks").asLong()).isZero();
        assertThat(reportA.at("/packs/variationChecks/checks").asLong()).isZero();
        assertThat(reportA.at("/packs/personalizationScreening/checks").asLong()).isZero();
        assertThat(forA).contains("rule_only_a").doesNotContain("rule_only_b").doesNotContain("MARK_ONLY_B");
        JsonNode reportB = mapper.readTree(forB);
        assertThat(reportB.at("/packs/listingRules/checks").asLong()).isEqualTo(3);
        assertThat(reportB.at("/packs/trademarkRisk/checks").asLong()).isEqualTo(1);
        assertThat(reportB.at("/packs/variationChecks/checks").asLong()).isEqualTo(1);
        assertThat(reportB.at("/packs/personalizationScreening/checks").asLong()).isEqualTo(1);
        assertThat(forB).contains("rule_only_b").contains("MARK_ONLY_B").doesNotContain("rule_only_a");
    }

    @Test
    void anEmptyPeriodAnswersZerosWithEveryFixedKeyAndNoError() throws Exception {
        TestClient nobody = client();

        // the second period is in the future and touches two ISO weeks (a Tuesday to the next Monday): both are partial
        for (String[] period : new String[][] {{WEEK_FROM, WEEK_TO, "1"}, {"2030-01-01", "2030-01-07", "2"}}) {
            JsonNode r = report(nobody, period[0], period[1], null);

            assertThat(r.at("/packs/listingRules/checks").asLong()).isZero();
            assertThat(names(r.at("/packs/listingRules/findings"), "severity")).containsExactly("ERROR", "WARNING", "INFO");
            assertThat(r.at("/packs/listingRules/findings").findValues("findings")).allMatch(n -> n.asLong() == 0);
            assertThat(r.at("/packs/listingRules/statuses/COMPLETED").asLong()).isZero();
            assertThat(r.at("/packs/listingRules/statuses/FAILED").asLong()).isZero();
            assertThat(r.at("/packs/listingRules/readiness/readyRate").isNull()).isTrue();
            assertThat(r.at("/packs/listingRules/readiness/averageScore").isNull()).isTrue();
            assertThat(r.at("/packs/listingRules/readiness/checksWithoutReadiness").asLong()).isZero();
            assertThat(r.at("/packs/listingRules/topRules/items")).isEmpty();
            assertThat(r.at("/packs/listingRules/topRules/distinctRules").asLong()).isZero();
            for (String level : List.of("HIGH", "MEDIUM", "LOW", "UNKNOWN")) {
                assertThat(r.at("/packs/trademarkRisk/riskLevels/" + level).asLong()).isZero();
                assertThat(r.at("/packs/personalizationScreening/trademarkRiskLevels/" + level).asLong()).isZero();
            }
            assertThat(r.at("/packs/trademarkRisk/completeness/COMPLETE").asLong()).isZero();
            assertThat(r.at("/packs/trademarkRisk/topMarks/items")).isEmpty();
            assertThat(r.at("/packs/personalizationScreening/trademarkStatus/NOT_CONFIGURED").asLong()).isZero();
            assertThat(r.at("/byWeek")).hasSize(Integer.parseInt(period[2]));
            assertThat(r.at("/byWeek/0/listingRules/checks").asLong()).isZero();
            assertThat(r.at("/byWeek/0/trademarkRisk/riskLevels/UNKNOWN").asLong()).isZero();
            assertThat(r.at("/byWeek/0/partial").asBoolean()).isEqualTo(period[2].equals("2"));
        }
    }

    @Test
    void theReportIsFreeWritesNothingAndIsRefusedForNeitherPlanNorQuota() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);
        UUID listing = data.listing(free.id(), "ETSY", t("2026-11-03T10:00:00Z"));
        data.finding(listing, "WARNING", "title_repeated_words");
        setUsed(jdbc, free.id(), NOVEMBER, quota(jdbc, PlanCode.FREE));
        List<Integer> before = tableCounts();

        ResultActions response = get(free, WEEK_FROM, WEEK_TO, null);

        // the plan has no trademark pack and the month's quota is used up: neither matters to a report of the client's own numbers
        response.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        JsonNode r = mapper.readTree(response.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.at("/packs/listingRules/checks").asLong()).isEqualTo(1);
        assertThat(tableCounts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT used FROM client_usage WHERE client_id = ?", Integer.class, free.id()))
                .isEqualTo(quota(jdbc, PlanCode.FREE));
    }

    private List<Integer> tableCounts() {
        List<Integer> counts = new ArrayList<>();
        for (String table : List.of("listing_check", "finding", "trademark_check", "trademark_check_finding", "variation_check",
                "variation_check_finding", "personalization_screening", "personalization_screening_finding", "client_usage",
                "client", "api_key")) {
            counts.add(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
        }
        return counts;
    }

    // ---- the request

    @Test
    void thePeriodAndTheMarketplaceAreChecked() throws Exception {
        TestClient c = client();

        get(c, "2026-11-08", "2026-11-02", null).andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("invalid-report-period"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        get(c, null, WEEK_TO, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-report-period"));
        get(c, WEEK_FROM, null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-report-period"));
        get(c, "2026-11-02", "08/11/2026", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-report-period"));

        // 92 days is the limit: 2026-01-01 to 2026-04-02 passes, one day more does not
        get(c, "2026-01-01", "2026-04-02", null).andExpect(status().isOk());
        get(c, "2026-01-01", "2026-04-03", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("report-period-too-long"))
                .andExpect(jsonPath("$.maxDays").value(92))
                .andExpect(jsonPath("$.requestedDays").value(93));

        get(c, WEEK_FROM, WEEK_TO, "NOPE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown-marketplace"));
        get(c, WEEK_FROM, WEEK_TO, "WALMART").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("marketplace-not-yet-supported"));
        get(c, WEEK_FROM, WEEK_TO, "  ").andExpect(status().isOk());
    }

    @Test
    void aRequestWithoutAnApiKeyIsRefused() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(PATH).param("from", WEEK_FROM).param("to", WEEK_TO)).andExpect(status().isUnauthorized());
    }

    @Test
    void theMarketplaceFiltersEveryPack() throws Exception {
        TestClient c = client();
        Instant created = t("2026-11-03T10:00:00Z");
        data.listing(c.id(), "ETSY", created);
        data.listing(c.id(), "AMAZON", created);
        data.listing(c.id(), "AMAZON", created);
        data.trademark(c.id(), "AMAZON", created, after30Days(created), "COMPLETE", "LOW");
        data.variation(c.id(), "ETSY", created, after30Days(created), "COMPLETE");

        JsonNode all = report(c, WEEK_FROM, WEEK_TO, null);
        JsonNode amazon = report(c, WEEK_FROM, WEEK_TO, "amazon");

        assertThat(all.at("/marketplace").isNull()).isTrue();
        assertThat(all.at("/packs/listingRules/checks").asLong()).isEqualTo(3);
        assertThat(amazon.at("/marketplace").asText()).isEqualTo("AMAZON");
        assertThat(amazon.at("/packs/listingRules/checks").asLong()).isEqualTo(2);
        assertThat(amazon.at("/packs/trademarkRisk/checks").asLong()).isEqualTo(1);
        assertThat(amazon.at("/packs/variationChecks/checks").asLong()).isZero();
        assertThat(names(amazon.at("/versions/rulesets"), "marketplace")).containsOnly("AMAZON");
    }

    @Test
    void theVersionsAreWhatRunsNow() throws Exception {
        JsonNode r = week(client());

        List<String> pairs = new ArrayList<>();
        r.at("/versions/rulesets").forEach(item -> pairs.add(item.get("pack").asText() + "/" + item.get("marketplace").asText()
                + "/" + item.get("version").asText()));
        assertThat(pairs).contains("listing-rules/ETSY/" + rulesets.get("ETSY").config().version(),
                "listing-rules/AMAZON/" + rulesets.get("AMAZON").config().version());
        assertThat(pairs).anyMatch(pair -> pair.startsWith("trademark-risk/ETSY/"))
                .anyMatch(pair -> pair.startsWith("variation-checks/AMAZON/"))
                .anyMatch(pair -> pair.startsWith("personalization-screening/ETSY/"));
        assertThat(r.at("/versions/trademarkCatalog/state").asText()).isEqualTo("READY");
        assertThat(r.at("/versions/trademarkCatalog/version").asText()).isEqualTo(TrademarkReadyTestConfig.catalogVersion());
    }

    // ---- listing rules

    @Test
    void theRulesWithTheMostFindingsAreRankedAndCounted() throws Exception {
        TestClient c = client();
        Instant created = t("2026-11-03T10:00:00Z");
        UUID c1 = data.listing(c.id(), "ETSY", created);
        UUID c2 = data.listing(c.id(), "ETSY", created);
        UUID c3 = data.listing(c.id(), "ETSY", created);
        data.finding(c1, "ERROR", "rule_hot");
        data.finding(c1, "ERROR", "rule_hot");
        data.finding(c2, "ERROR", "rule_hot");
        data.finding(c1, "WARNING", "rule_b");
        data.finding(c3, "WARNING", "rule_b");
        data.finding(c1, "WARNING", "rule_a");
        data.finding(c2, "ERROR", "rule_dual");
        data.finding(c2, "WARNING", "rule_dual");
        data.finding(c1, "ERROR", null);
        for (int i = 1; i <= 8; i++) {
            data.finding(c3, "INFO", "zz0" + i);
        }

        JsonNode r = week(c);

        JsonNode top = r.at("/packs/listingRules/topRules");
        assertThat(top.get("limit").asInt()).isEqualTo(10);
        assertThat(top.get("distinctRules").asLong()).as("every rule and severity before the cut, a finding with no rule id left out").isEqualTo(13);
        List<String> ranked = new ArrayList<>();
        top.get("items").forEach(item -> ranked.add(item.get("ruleId").asText() + "/" + item.get("severity").asText() + "/"
                + item.get("findings").asLong() + "/" + item.get("checksAffected").asLong()));
        assertThat(ranked).containsExactly("rule_hot/ERROR/3/2", "rule_b/WARNING/2/2", "rule_a/WARNING/1/1", "rule_dual/ERROR/1/1",
                "rule_dual/WARNING/1/1", "zz01/INFO/1/1", "zz02/INFO/1/1", "zz03/INFO/1/1", "zz04/INFO/1/1", "zz05/INFO/1/1");
        // the severity block counts every finding, the one without a rule id too
        JsonNode severities = r.at("/packs/listingRules/findings");
        assertThat(severities.get(0).get("findings").asLong()).isEqualTo(5);
        assertThat(severities.get(0).get("checksAffected").asLong()).isEqualTo(2);
        assertThat(severities.get(1).get("findings").asLong()).isEqualTo(4);
        assertThat(severities.get(1).get("checksAffected").asLong()).isEqualTo(3);
        assertThat(severities.get(2).get("findings").asLong()).isEqualTo(8);
        assertThat(severities.get(2).get("checksAffected").asLong()).isEqualTo(1);
        assertThat(r.at("/packs/listingRules/checks").asLong()).isEqualTo(3);
        assertThat(r.at("/packs/listingRules/statuses/COMPLETED").asLong()).isEqualTo(3);
    }

    @Test
    void readinessThatIsNotStoredIsSkippedAndCounted() throws Exception {
        TestClient c = client();
        Instant created = t("2026-11-03T10:00:00Z");
        data.listing(c.id(), "ETSY", created, Readiness.scored(80, true));
        data.listing(c.id(), "ETSY", created, Readiness.scored(90, false));
        data.listing(c.id(), "ETSY", created, Readiness.incomplete());
        data.listing(c.id(), "ETSY", created);
        data.listing(c.id(), "ETSY", created);

        JsonNode readiness = week(c).at("/packs/listingRules/readiness");

        assertThat(readiness.get("checksWithReadiness").asLong()).isEqualTo(3);
        assertThat(readiness.get("scoredChecks").asLong()).isEqualTo(2);
        assertThat(readiness.get("incompleteChecks").asLong()).isEqualTo(1);
        assertThat(readiness.get("readyChecks").asLong()).isEqualTo(1);
        assertThat(readiness.get("readyRate").asDouble()).isEqualTo(0.3333);
        assertThat(readiness.get("averageScore").asDouble()).as("the incomplete one has no score and is not a zero").isEqualTo(85.0);
        assertThat(readiness.get("checksWithoutReadiness").asLong()).isEqualTo(2);
        assertThat(readiness.get("versions")).hasSize(1);
        assertThat(readiness.at("/versions/0").asText()).isEqualTo(HealthReportTestData.READINESS_VERSION);
    }

    // ---- weeks

    @Test
    void weeksFollowTheIsoBoundariesAndTheirSumIsTheTotal() throws Exception {
        TestClient c = client();
        for (String instant : List.of("2026-11-01T23:59:59.999Z", "2026-11-02T00:00:00Z", "2026-11-08T23:59:59.999Z",
                "2026-11-09T00:00:00Z", "2026-11-15T23:59:59Z", "2026-11-16T00:00:00Z")) {
            data.listing(c.id(), "ETSY", t(instant));
        }

        JsonNode r = report(c, "2026-11-02", "2026-11-15", null);

        assertThat(r.at("/period/days").asInt()).isEqualTo(14);
        assertThat(r.at("/packs/listingRules/checks").asLong()).as("the two outside the period are not counted").isEqualTo(4);
        assertThat(names(r.get("byWeek"), "isoWeek")).containsExactly("2026-W45", "2026-W46");
        assertThat(r.at("/byWeek/0/listingRules/checks").asLong()).isEqualTo(2);
        assertThat(r.at("/byWeek/1/listingRules/checks").asLong()).isEqualTo(2);
        assertThat(r.at("/byWeek/0/from").asText()).isEqualTo("2026-11-02");
        assertThat(r.at("/byWeek/0/to").asText()).isEqualTo("2026-11-08");
        assertThat(r.at("/byWeek/1/from").asText()).isEqualTo("2026-11-09");
        assertThat(r.at("/byWeek/1/to").asText()).isEqualTo("2026-11-15");
        assertThat(r.get("byWeek").findValues("partial")).allMatch(partial -> !partial.asBoolean());
    }

    @Test
    void aPeriodAcrossTheYearEndHasWeek53AndClipsItsPartialWeeks() throws Exception {
        clock.set(t("2027-01-12T00:00:00Z"));
        TestClient c = client();
        for (String instant : List.of("2026-12-23T00:00:00Z", "2026-12-27T23:59:59Z", "2026-12-28T00:00:00Z", "2027-01-03T23:59:59Z",
                "2027-01-04T00:00:00Z", "2027-01-08T12:00:00Z")) {
            data.listing(c.id(), "ETSY", t(instant));
        }

        JsonNode r = report(c, "2026-12-23", "2027-01-08", null);

        assertThat(names(r.get("byWeek"), "isoWeek")).containsExactly("2026-W52", "2026-W53", "2027-W01");
        List<Long> counts = new ArrayList<>();
        r.get("byWeek").forEach(week -> counts.add(week.at("/listingRules/checks").asLong()));
        assertThat(counts).containsExactly(2L, 2L, 2L);
        assertThat(counts.stream().mapToLong(Long::longValue).sum()).isEqualTo(r.at("/packs/listingRules/checks").asLong());
        assertThat(r.at("/byWeek/0/from").asText()).isEqualTo("2026-12-23");
        assertThat(r.at("/byWeek/0/to").asText()).isEqualTo("2026-12-27");
        assertThat(r.at("/byWeek/0/partial").asBoolean()).isTrue();
        assertThat(r.at("/byWeek/1/from").asText()).isEqualTo("2026-12-28");
        assertThat(r.at("/byWeek/1/to").asText()).isEqualTo("2027-01-03");
        assertThat(r.at("/byWeek/1/partial").asBoolean()).isFalse();
        assertThat(r.at("/byWeek/2/to").asText()).isEqualTo("2027-01-08");
        assertThat(r.at("/byWeek/2/partial").asBoolean()).isTrue();
    }

    // ---- retention

    @Test
    void resultsPastTheirRetentionAreNotCountedWhateverTheCleanupDidButListingRulesAre() throws Exception {
        TestClient c = client();
        Instant kept = t("2026-11-03T10:00:00Z");
        Instant expired = t("2026-10-05T10:00:00Z");
        Instant exactlyNow = NOW.minus(Duration.ofDays(30));
        // listing-rules has no retention: the old one counts
        UUID oldListing = data.listing(c.id(), "ETSY", expired);
        data.finding(oldListing, "WARNING", "rule_old");
        data.listing(c.id(), "ETSY", kept);

        UUID tmKept = data.trademark(c.id(), "ETSY", kept, after30Days(kept), "COMPLETE", "LOW");
        data.trademarkFinding(tmKept, 0, "catalog_exact", "LOW", "MARK_KEPT");
        UUID tmExpired = data.trademark(c.id(), "ETSY", expired, after30Days(expired), "COMPLETE", "HIGH");
        data.trademarkFinding(tmExpired, 0, "catalog_exact", "HIGH", "MARK_EXPIRED");
        data.trademark(c.id(), "ETSY", exactlyNow, after30Days(exactlyNow), "COMPLETE", "HIGH");

        data.variation(c.id(), "ETSY", kept, after30Days(kept), "COMPLETE");
        UUID varExpired = data.variation(c.id(), "ETSY", expired, after30Days(expired), "COMPLETE");
        data.variationFinding(varExpired, 0, "rule_expired", "WARNING");
        data.variation(c.id(), "ETSY", exactlyNow, after30Days(exactlyNow), "INCOMPLETE");

        data.screening(c.id(), "ETSY", kept, after30Days(kept), "COMPLETE", "COMPLETE", "LOW");
        UUID screenExpired = data.screening(c.id(), "ETSY", expired, after30Days(expired), "COMPLETE", "COMPLETE", "HIGH");
        data.screeningFinding(screenExpired, 0, "rule_screen_expired", "WARNING");
        data.screening(c.id(), "ETSY", exactlyNow, after30Days(exactlyNow), "INCOMPLETE", "NOT_CONFIGURED", "UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_check WHERE client_id = ?", Integer.class, c.id()))
                .as("the expired rows are still in the table: nothing cleaned them").isEqualTo(3);

        String body = body(c, "2026-10-01", "2026-11-08", null);
        JsonNode r = mapper.readTree(body);

        assertThat(r.at("/packs/listingRules/checks").asLong()).isEqualTo(2);
        assertThat(r.at("/packs/trademarkRisk/checks").asLong()).isEqualTo(1);
        assertThat(r.at("/packs/trademarkRisk/riskLevels/HIGH").asLong()).isZero();
        assertThat(r.at("/packs/variationChecks/checks").asLong()).isEqualTo(1);
        assertThat(r.at("/packs/personalizationScreening/checks").asLong()).isEqualTo(1);
        assertThat(body).contains("MARK_KEPT").contains("rule_old").doesNotContain("MARK_EXPIRED").doesNotContain("rule_expired")
                .doesNotContain("rule_screen_expired");

        // coverage comes from the retention settings (30 days here): a period that starts before 2026-10-11 has holes
        assertThat(r.at("/coverageNote/complete").asBoolean()).isFalse();
        assertThat(names(r.at("/coverageNote/retention"), "pack")).containsExactly("trademark-risk", "variation-checks",
                "personalization-screening");
        assertThat(names(r.at("/coverageNote/retention"), "period")).containsOnly("P30D");
        assertThat(names(r.at("/coverageNote/retention"), "coveredFrom")).containsOnly("2026-10-11");
        assertThat(r.at("/coverageNote/text").asText()).contains("complete from 2026-10-11");
        assertThat(week(c).at("/coverageNote/complete").asBoolean()).isTrue();
    }

    // ---- trademark

    @Test
    void trademarkRiskLevelsKeepUnknownApartAndMarksAreRankedWithTheirHighestRisk() throws Exception {
        TestClient c = client();
        Instant created = t("2026-11-03T10:00:00Z");
        Instant until = after30Days(created);
        UUID high = data.trademark(c.id(), "ETSY", created, until, "COMPLETE", "HIGH");
        data.trademarkFinding(high, 0, "catalog_exact", "HIGH", "ZORVEX");
        data.trademarkFinding(high, 1, "catalog_fuzzy", "MEDIUM", "ZORVEX");
        data.trademarkFinding(high, 2, "pure_color_description", "LOW", null);
        UUID low = data.trademark(c.id(), "ETSY", created, until, "COMPLETE", "LOW");
        data.trademarkFinding(low, 0, "catalog_exact", "LOW", "ZORVEX");
        UUID incomplete = data.trademark(c.id(), "ETSY", created, until, "INCOMPLETE", "UNKNOWN");
        data.trademarkFinding(incomplete, 0, "catalog_fuzzy", "MEDIUM", "QUARTZEN");
        data.trademark(c.id(), "ETSY", created, until, "COMPLETE", "UNKNOWN");
        UUID script = data.trademark(c.id(), "ETSY", created, until, "COMPLETE", "MEDIUM");
        data.trademarkFinding(script, 0, "catalog_exact", "MEDIUM", "<script>alert(1)</script>");

        JsonNode r = week(c).at("/packs/trademarkRisk");

        assertThat(r.get("checks").asLong()).isEqualTo(5);
        assertThat(r.at("/completeness/COMPLETE").asLong()).isEqualTo(4);
        assertThat(r.at("/completeness/INCOMPLETE").asLong()).isEqualTo(1);
        assertThat(r.at("/riskLevels/HIGH").asLong()).isEqualTo(1);
        assertThat(r.at("/riskLevels/MEDIUM").asLong()).isEqualTo(1);
        assertThat(r.at("/riskLevels/LOW").asLong()).isEqualTo(1);
        assertThat(r.at("/riskLevels/UNKNOWN").asLong()).as("UNKNOWN is its own level and is never added to LOW").isEqualTo(2);
        JsonNode marks = r.get("topMarks");
        assertThat(marks.get("distinctMarks").asLong()).as("a finding with no mark is not a mark").isEqualTo(3);
        assertThat(names(marks.get("items"), "mark")).containsExactly("ZORVEX", "<script>alert(1)</script>", "QUARTZEN");
        assertThat(marks.at("/items/0/findings").asLong()).isEqualTo(3);
        assertThat(marks.at("/items/0/checksAffected").asLong()).isEqualTo(2);
        assertThat(marks.at("/items/0/highestRisk").asText()).isEqualTo("HIGH");
        assertThat(marks.at("/items/1/highestRisk").asText()).isEqualTo("MEDIUM");
        assertThat(marks.at("/items/2/highestRisk").asText()).isEqualTo("MEDIUM");
    }

    @Test
    void variationAndPersonalizationCountTheirOwnResultsAndFindings() throws Exception {
        TestClient c = client();
        Instant created = t("2026-11-04T10:00:00Z");
        Instant until = after30Days(created);
        UUID v1 = data.variation(c.id(), "ETSY", created, until, "COMPLETE");
        data.variationFinding(v1, 0, "duplicate_sku", "WARNING");
        data.variationFinding(v1, 1, "duplicate_sku", "WARNING");
        UUID v2 = data.variation(c.id(), "ETSY", created, until, "INCOMPLETE");
        data.variationFinding(v2, 0, "duplicate_sku", "WARNING");
        data.variationFinding(v2, 1, "variation_theme_allowed", "INFO");
        UUID s1 = data.screening(c.id(), "ETSY", created, until, "COMPLETE", "COMPLETE", "LOW");
        data.screeningFinding(s1, 0, "unsupported_characters", "WARNING");
        data.screeningTrademarkFinding(s1, 1, "catalog_exact", "HIGH", "PERS_MARK_NOT_SHOWN");
        UUID s2 = data.screening(c.id(), "ETSY", created, until, "INCOMPLETE", "NOT_CONFIGURED", "UNKNOWN");
        data.screeningFinding(s2, 0, "too_long", "ERROR");

        String body = body(c, WEEK_FROM, WEEK_TO, null);
        JsonNode r = mapper.readTree(body);

        assertThat(r.at("/packs/variationChecks/checks").asLong()).isEqualTo(2);
        assertThat(r.at("/packs/variationChecks/completeness/COMPLETE").asLong()).isEqualTo(1);
        assertThat(r.at("/packs/variationChecks/completeness/INCOMPLETE").asLong()).isEqualTo(1);
        assertThat(r.at("/packs/variationChecks/findings/1/findings").asLong()).isEqualTo(3);
        assertThat(r.at("/packs/variationChecks/findings/1/checksAffected").asLong()).isEqualTo(2);
        assertThat(r.at("/packs/variationChecks/topRules/items/0/ruleId").asText()).isEqualTo("duplicate_sku");
        assertThat(r.at("/packs/variationChecks/topRules/items/0/findings").asLong()).isEqualTo(3);
        assertThat(r.at("/packs/variationChecks/topRules/distinctRules").asLong()).isEqualTo(2);

        JsonNode p = r.at("/packs/personalizationScreening");
        assertThat(p.get("checks").asLong()).isEqualTo(2);
        assertThat(p.at("/trademarkStatus/COMPLETE").asLong()).isEqualTo(1);
        assertThat(p.at("/trademarkStatus/NOT_CONFIGURED").asLong()).isEqualTo(1);
        assertThat(p.at("/trademarkRiskLevels/UNKNOWN").asLong()).isEqualTo(1);
        assertThat(p.at("/trademarkRiskLevels/LOW").asLong()).isEqualTo(1);
        assertThat(p.at("/findings/0/findings").asLong()).as("the character rules only: ERROR from too_long").isEqualTo(1);
        assertThat(p.at("/findings/1/findings").asLong()).isEqualTo(1);
        assertThat(names(p.at("/topRules/items"), "ruleId")).containsExactlyInAnyOrder("too_long", "unsupported_characters");
        assertThat(body).as("a screening's marks say something about what a buyer typed").doesNotContain("PERS_MARK_NOT_SHOWN");
    }

    // ---- what never leaves the database

    @Test
    void noTextAClientTypedAndNoCheckIdIsInTheAnswer() throws Exception {
        String secret = "SECRET-TYPED-TEXT-7731";
        TestClient c = client();
        Instant created = t("2026-11-03T10:00:00Z");
        Instant until = after30Days(created);
        List<UUID> ids = new ArrayList<>();
        UUID listing = data.listing(c.id(), "ETSY", created);
        ids.add(listing);
        data.finding(listing, "ERROR", "title_length", secret);
        UUID trademark = data.trademark(c.id(), "ETSY", created, until, "COMPLETE", "HIGH");
        ids.add(trademark);
        data.trademarkFinding(trademark, 0, "catalog_exact", "HIGH", "TM_MARK_X", secret);
        UUID variation = data.variation(c.id(), "ETSY", created, until, "COMPLETE");
        ids.add(variation);
        data.variationFinding(variation, 0, "duplicate_sku", "WARNING", secret);
        UUID screening = data.screening(c.id(), "ETSY", created, until, "COMPLETE", "COMPLETE", "LOW");
        ids.add(screening);
        data.screeningFinding(screening, 0, "unsupported_characters", "WARNING", secret);

        String body = body(c, WEEK_FROM, WEEK_TO, null);

        assertThat(body).doesNotContain(secret).contains("TM_MARK_X").contains("title_length");
        for (UUID id : ids) {
            assertThat(body).doesNotContain(id.toString());
        }
        for (String hash : jdbc.queryForList("SELECT input_hash FROM trademark_check WHERE client_id = ?", String.class, c.id())) {
            assertThat(body).doesNotContain(hash.trim());
        }
        for (String field : List.of("snippet", "suggestion", "message", "relatedFields", "inputHash", "checkId")) {
            assertThat(mapper.readTree(body).findValues(field)).as(field).isEmpty();
        }
    }

    @Test
    void withTheConsoleOffThereIsNoReportPage() throws Exception {
        TestClient c = client();

        // the console of this context is off (the default): the page has no route, with or without a key
        mvc.perform(MockMvcRequestBuilders.get("/console/report").header(HEADER, c.key())).andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/console/report")).andExpect(status().isUnauthorized());
    }

    // ---- the contract

    @Test
    void theExampleOfTheSpecHasTheFieldsOfTheRealAnswer() throws Exception {
        TestClient c = client();
        Instant created = t("2026-11-03T10:00:00Z");
        Instant until = after30Days(created);
        UUID listing = data.listing(c.id(), "ETSY", created, Readiness.scored(80, true));
        data.finding(listing, "ERROR", "title_length");
        UUID trademark = data.trademark(c.id(), "ETSY", created, until, "COMPLETE", "HIGH");
        data.trademarkFinding(trademark, 0, "catalog_exact", "HIGH", "ZORVEX");
        UUID variation = data.variation(c.id(), "ETSY", created, until, "COMPLETE");
        data.variationFinding(variation, 0, "duplicate_sku", "WARNING");
        UUID screening = data.screening(c.id(), "ETSY", created, until, "COMPLETE", "COMPLETE", "LOW");
        data.screeningFinding(screening, 0, "unsupported_characters", "WARNING");

        JsonNode actual = week(c);
        JsonNode example = mapper.readTree(ApiExamples.REPORT_SUMMARY);

        assertThat(shape(actual)).isEqualTo(shape(example));
    }

    /** The names an answer is made of: the keys of every object, and the shape of the first item of every list. */
    private static Object shape(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, Object> shape = new TreeMap<>();
            node.fields().forEachRemaining(entry -> shape.put(entry.getKey(), shape(entry.getValue())));
            return shape;
        }
        if (node.isArray()) {
            return node.isEmpty() ? List.of() : List.of(shape(node.get(0)));
        }
        return "value";
    }
}
