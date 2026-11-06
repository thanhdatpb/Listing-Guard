package com.thanhdatpb.listingguard.api.plan;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.body;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.countOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.quota;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.setUsed;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Plans on (ADR-013), through the HTTP API: the pack comes first, a cached result costs nothing even at the limit, the
 * first result over the limit is a 429 with its own code and no Retry-After, a full check of one listing is two units,
 * concurrent requests cannot take a client past its quota, the month changes at 00:00 UTC, and {@code GET /usage} shows
 * only the caller's own plan.
 */
@SpringBootTest(properties = "listing-guard.plans.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class PlanQuotaApiIntegrationTest {

    private static final String CHECKS = "/api/v1/checks";
    private static final String TRADEMARK = "/api/v1/trademark-checks";
    private static final String USAGE = "/api/v1/usage";

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

    @BeforeEach
    void startInTheMiddleOfNovember() {
        clock.set(PlanTestSupport.NOVEMBER_15);
    }

    private TestClient client(PlanCode plan) {
        return newClient(provisioning, clients, plan);
    }

    private ResultActions post(TestClient client, String path, String title) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).header(HEADER, client.key())
                .contentType(MediaType.APPLICATION_JSON).content(body(title)));
    }

    private ResultActions usage(TestClient client) throws Exception {
        return mvc.perform(get(USAGE).header(HEADER, client.key()));
    }

    private int used(TestClient client) {
        return PlanTestSupport.used(jdbc, client.id(), NOVEMBER);
    }

    private static String listing() {
        return "Plain ceramic mug " + unique();
    }

    // ---- the pack comes first

    @Test
    void aPackOutsideThePlanIsA403WithThePlanAndThePackAndCostsNothing() throws Exception {
        TestClient free = client(PlanCode.FREE);

        post(free, TRADEMARK, listing())
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("plan-not-allowed"))
                .andExpect(jsonPath("$.type").value("urn:listing-guard:problem:plan-not-allowed"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.pack").value("trademark-risk"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        assertThat(used(free)).isZero();
        assertThat(countOf(jdbc, "trademark_check", free.id())).isZero();
        post(free, CHECKS, listing()).andExpect(status().isOk());
        post(client(PlanCode.SELLER), TRADEMARK, listing()).andExpect(status().isOk());
        post(client(PlanCode.PRO), TRADEMARK, listing()).andExpect(status().isOk());
    }

    @Test
    void thePackIsCheckedBeforeTheCacheAndBeforeTheQuota() throws Exception {
        TestClient free = client(PlanCode.FREE);
        String title = listing();
        post(free, CHECKS, title).andExpect(status().isOk());
        setUsed(jdbc, free.id(), NOVEMBER, quota(jdbc, PlanCode.FREE));
        jdbc.update("DELETE FROM plan_pack WHERE plan_code = 'FREE' AND pack_id = 'listing-rules'");
        try {
            // the result is stored and the quota is used up, and still the answer is the pack's
            post(free, CHECKS, title).andExpect(status().isForbidden()).andExpect(jsonPath("$.pack").value("listing-rules"));
        } finally {
            jdbc.update("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', 'listing-rules')");
        }
    }

    // ---- the quota

    @Test
    void aCachedResultCostsNothingAndIsStillAnsweredAtTheLimit() throws Exception {
        TestClient free = client(PlanCode.FREE);
        int quota = quota(jdbc, PlanCode.FREE);
        setUsed(jdbc, free.id(), NOVEMBER, quota - 1);
        String title = listing();

        post(free, CHECKS, title).andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false));
        assertThat(used(free)).isEqualTo(quota);

        post(free, CHECKS, title).andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(true));
        assertThat(used(free)).as("a cached result is not counted").isEqualTo(quota);
        post(free, CHECKS, listing()).andExpect(status().isTooManyRequests());
        assertThat(used(free)).isEqualTo(quota);
    }

    @Test
    void theFirstResultOverTheLimitIsA429QuotaExceededWithItsMembersAndNoRetryAfter() throws Exception {
        TestClient free = client(PlanCode.FREE);
        int quota = quota(jdbc, PlanCode.FREE);
        setUsed(jdbc, free.id(), NOVEMBER, quota);

        MvcResult result = post(free, CHECKS, listing())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Content-Type", Matchers.startsWith("application/problem+json")))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.code").value("quota-exceeded"))
                .andExpect(jsonPath("$.type").value("urn:listing-guard:problem:quota-exceeded"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.quota").value(quota))
                .andExpect(jsonPath("$.used").value(quota))
                .andExpect(jsonPath("$.resetAt").value("2026-12-01T00:00:00Z"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("Plain ceramic mug");
        assertThat(countOf(jdbc, "listing_check", free.id())).as("nothing is stored for a refused request").isZero();
        assertThat(used(free)).isEqualTo(quota);
    }

    @Test
    void aFullCheckOfOneListingIsTwoUnitsAndAnIdenticalSecondOneIsNone() throws Exception {
        TestClient seller = client(PlanCode.SELLER);
        String title = listing();

        post(seller, CHECKS, title).andExpect(status().isOk());
        post(seller, TRADEMARK, title).andExpect(status().isOk());
        assertThat(used(seller)).isEqualTo(2);

        post(seller, CHECKS, title).andExpect(jsonPath("$.cached").value(true));
        post(seller, TRADEMARK, title).andExpect(jsonPath("$.cached").value(true));
        assertThat(used(seller)).isEqualTo(2);
    }

    @Test
    void aFailedRequestAndAReadCostNothing() throws Exception {
        TestClient seller = client(PlanCode.SELLER);

        mvc.perform(MockMvcRequestBuilders.post(CHECKS).header(HEADER, seller.key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"NOPE\",\"listing\":{}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.post(CHECKS).header(HEADER, seller.key()).contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());
        String id = mapper.readTree(post(seller, CHECKS, listing()).andReturn().getResponse().getContentAsString()).get("checkId").asText();
        assertThat(used(seller)).isEqualTo(1);

        mvc.perform(get(CHECKS + "/" + id).header(HEADER, seller.key())).andExpect(status().isOk());
        usage(seller).andExpect(status().isOk());

        assertThat(used(seller)).isEqualTo(1);
    }

    // ---- GET /usage

    @Test
    void usageShowsThePlanTheQuotaTheUseTheResetAndThePacks() throws Exception {
        TestClient seller = client(PlanCode.SELLER);
        int quota = quota(jdbc, PlanCode.SELLER);
        post(seller, CHECKS, listing()).andExpect(status().isOk());

        String json = usage(seller).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.startsWith("application/json")))
                .andReturn().getResponse().getContentAsString();

        JsonNode usage = mapper.readTree(json);
        assertThat(usage.get("enforced").asBoolean()).isTrue();
        assertThat(usage.get("plan").asText()).isEqualTo("SELLER");
        assertThat(usage.get("monthlyQuota").asInt()).isEqualTo(quota);
        assertThat(usage.get("used").asInt()).isEqualTo(1);
        assertThat(usage.get("remaining").asInt()).isEqualTo(quota - 1);
        assertThat(usage.get("periodStart").asText()).isEqualTo("2026-11-01");
        assertThat(usage.get("resetAt").asText()).isEqualTo("2026-12-01T00:00:00Z");
        List<String> packs = new ArrayList<>();
        usage.get("packs").forEach(pack -> packs.add(pack.asText()));
        // the packs of the plan as the table has them (sorted), not a list repeated here
        assertThat(packs).containsExactlyElementsOf(jdbc.queryForList(
                "SELECT pack_id FROM plan_pack WHERE plan_code = 'SELLER' ORDER BY pack_id", String.class));
        assertThat(packs).contains(Pack.TRADEMARK_RISK.id(), Pack.VARIATION_CHECKS.id());
    }

    @Test
    void usageIsNeverRefusedForPlanOrQuotaAndOneClientNeverSeesAnothers() throws Exception {
        TestClient free = client(PlanCode.FREE);
        TestClient pro = client(PlanCode.PRO);
        int freeQuota = quota(jdbc, PlanCode.FREE);
        setUsed(jdbc, free.id(), NOVEMBER, freeQuota);
        setUsed(jdbc, pro.id(), NOVEMBER, 7);

        usage(free).andExpect(status().isOk()).andExpect(jsonPath("$.used").value(freeQuota))
                .andExpect(jsonPath("$.remaining").value(0)).andExpect(jsonPath("$.plan").value("FREE"));
        usage(pro).andExpect(status().isOk()).andExpect(jsonPath("$.used").value(7)).andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.packs[?(@ == 'policy-analyzer')]").exists());
        mvc.perform(get(USAGE)).andExpect(status().isUnauthorized());
    }

    @Test
    void aPlanWithoutAQuotaIsUnlimited() throws Exception {
        TestClient pro = client(PlanCode.PRO);
        int original = quota(jdbc, PlanCode.PRO);
        jdbc.update("UPDATE plan SET monthly_quota = NULL WHERE code = 'PRO'");
        try {
            setUsed(jdbc, pro.id(), NOVEMBER, 2_000_000);
            usage(pro).andExpect(jsonPath("$.monthlyQuota").doesNotExist())
                    .andExpect(jsonPath("$.remaining").doesNotExist()).andExpect(jsonPath("$.used").value(2_000_000));

            post(pro, CHECKS, listing()).andExpect(status().isOk());

            assertThat(used(pro)).isEqualTo(2_000_001);
        } finally {
            jdbc.update("UPDATE plan SET monthly_quota = ? WHERE code = 'PRO'", original);
        }
    }

    // ---- the month

    @Test
    void theMonthChangesAtMidnightUtc() throws Exception {
        TestClient free = client(PlanCode.FREE);
        int quota = quota(jdbc, PlanCode.FREE);
        setUsed(jdbc, free.id(), NOVEMBER, quota);

        clock.set(Instant.parse("2026-11-30T23:59:59Z"));
        post(free, CHECKS, listing()).andExpect(status().isTooManyRequests());
        usage(free).andExpect(jsonPath("$.periodStart").value("2026-11-01")).andExpect(jsonPath("$.used").value(quota));

        clock.set(Instant.parse("2026-12-01T00:00:00Z"));
        usage(free).andExpect(jsonPath("$.periodStart").value("2026-12-01")).andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.remaining").value(quota)).andExpect(jsonPath("$.resetAt").value("2027-01-01T00:00:00Z"));
        post(free, CHECKS, listing()).andExpect(status().isOk());

        assertThat(PlanTestSupport.used(jdbc, free.id(), java.time.LocalDate.of(2026, 12, 1))).isEqualTo(1);
        assertThat(used(free)).as("November keeps its own count").isEqualTo(quota);
    }

    // ---- concurrency

    private List<MvcResult> concurrently(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<MvcResult> call(TestClient client, String title) {
        return () -> mvc.perform(MockMvcRequestBuilders.post(CHECKS).header(HEADER, client.key())
                .contentType(MediaType.APPLICATION_JSON).content(body(title))).andReturn();
    }

    @Test
    void identicalRequestsAtTheSameTimeChargeExactlyOneUnit() throws Exception {
        TestClient seller = client(PlanCode.SELLER);
        String title = listing();
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(call(seller, title));
        }

        List<MvcResult> results = concurrently(calls);

        long fresh = 0;
        for (MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            if (!mapper.readTree(result.getResponse().getContentAsString()).get("cached").asBoolean()) {
                fresh++;
            }
        }
        assertThat(fresh).as("one request stored the result, the others were answered from it").isEqualTo(1);
        assertThat(countOf(jdbc, "listing_check", seller.id())).isEqualTo(1);
        assertThat(used(seller)).as("the requests that lost the race took their charge back with them").isEqualTo(1);
    }

    @Test
    void differentRequestsAtTheSameTimeCannotTakeAClientPastItsQuota() throws Exception {
        TestClient free = client(PlanCode.FREE);
        int quota = quota(jdbc, PlanCode.FREE);
        setUsed(jdbc, free.id(), NOVEMBER, quota - 1);
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(call(free, listing()));
        }

        List<MvcResult> results = concurrently(calls);

        long stored = results.stream().filter(result -> result.getResponse().getStatus() == 200).count();
        long refused = results.stream().filter(result -> result.getResponse().getStatus() == 429).count();
        assertThat(stored).as("one unit was left").isEqualTo(1);
        assertThat(refused).isEqualTo(7);
        assertThat(used(free)).isEqualTo(quota);
        assertThat(countOf(jdbc, "listing_check", free.id())).isEqualTo(1);
    }
}
