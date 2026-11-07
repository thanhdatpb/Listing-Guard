package com.thanhdatpb.listingguard.api.personalization;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.etsy;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.failingRulesets;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.unique;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.countOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.quota;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.setUsed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.personalization.Completeness;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plans and the monthly quota on the personalization endpoint (ADR-013, ADR-016), with plans on: the pack comes first, then
 * the cache, then the quota, and one unit is one new COMPLETE result. The quotas are read from the {@code plan} table. The
 * DEMO catalog is served, because without one no result is complete.
 */
@SpringBootTest(properties = "listing-guard.plans.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PlanTestClockConfig.class, PersonalizationDemoCatalogTestConfig.class})
class PersonalizationQuotaIntegrationTest {

    private static final String SCREENINGS = "/api/v1/personalization-screenings";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    MutableClock clock;

    @Autowired
    PlanService plans;

    @Autowired
    PersonalizationProperties properties;

    @Autowired
    ResourceLoader resources;

    @Autowired
    RulesetVersionRepository versions;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    NormalizerRegistry marketplaces;

    @Autowired
    PersonalizationNormalizer normalizer;

    @Autowired
    TrademarkCatalogRuntime trademarkRuntime;

    @Autowired
    PersonalizationScreeningStore store;

    @BeforeEach
    void startInTheMiddleOfNovember() {
        clock.set(PlanTestSupport.NOVEMBER_15);
    }

    private ResultActions perform(TestClient client, JsonNode body) throws Exception {
        return mvc.perform(post(SCREENINGS).header(HEADER, client.key()).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body)));
    }

    private JsonNode ok(TestClient client, JsonNode body) throws Exception {
        return MAPPER.readTree(perform(client, body).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private int used(TestClient client) {
        return PlanTestSupport.used(jdbc, client.id(), NOVEMBER);
    }

    private static JsonNode order() {
        return etsy(unique());
    }

    @Test
    void aPlanWithoutThePackIsRefusedBeforeAnythingElse() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);

        perform(free, order()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan-not-allowed"))
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.pack").value(Pack.PERSONALIZATION_SCREENING.id()));

        assertThat(countOf(jdbc, "personalization_screening", free.id())).as("nothing is stored for a refused request").isZero();
        assertThat(used(free)).isZero();
    }

    @Test
    void anUnknownPrintProfileFromAFreeClientIsStillThePlanThatAnswersFirst() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);

        perform(free, PersonalizationApiSupport.body("ETSY", "latin_fancy", null, "MAX")).andExpect(status().isForbidden());
    }

    @Test
    void sellerAndProMayUseThePackAndOneNewResultCostsOneUnitACachedOneNothing() throws Exception {
        for (PlanCode plan : new PlanCode[]{PlanCode.SELLER, PlanCode.PRO}) {
            TestClient client = newClient(provisioning, clients, plan);
            JsonNode body = order();

            assertThat(ok(client, body).get("cached").asBoolean()).isFalse();
            assertThat(used(client)).as(plan + " after a new result").isEqualTo(1);
            assertThat(ok(client, body).get("cached").asBoolean()).isTrue();
            assertThat(used(client)).as(plan + " after a cached one").isEqualTo(1);
        }
    }

    @Test
    void aRefusedPrintProfileCostsNothing() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);

        perform(seller, PersonalizationApiSupport.body("ETSY", "latin_fancy", null, "MAX")).andExpect(status().isBadRequest());

        assertThat(used(seller)).isZero();
        assertThat(countOf(jdbc, "personalization_screening", seller.id())).isZero();
    }

    @Test
    void aPlanThatLosesThePackIsRefused() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        jdbc.update("DELETE FROM plan_pack WHERE plan_code = 'SELLER' AND pack_id = ?", Pack.PERSONALIZATION_SCREENING.id());
        try {
            perform(seller, order()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan-not-allowed"));
            assertThat(used(seller)).isZero();
        } finally {
            jdbc.update("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('SELLER', ?) ON CONFLICT DO NOTHING",
                    Pack.PERSONALIZATION_SCREENING.id());
        }
        ok(seller, order());
    }

    @Test
    void atTheLimitACachedAnswerIsStillGivenAndANewOrderIsRefusedWithoutRetryAfter() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        JsonNode known = order();
        ok(seller, known);
        int quota = quota(jdbc, PlanCode.SELLER);
        setUsed(jdbc, seller.id(), NOVEMBER, quota);

        assertThat(ok(seller, known).get("cached").asBoolean()).as("already paid for").isTrue();
        perform(seller, order()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("quota-exceeded")).andExpect(jsonPath("$.plan").value("SELLER"))
                .andExpect(jsonPath("$.quota").value(quota)).andExpect(jsonPath("$.used").value(quota))
                .andExpect(jsonPath("$.resetAt").value("2026-12-01T00:00:00Z"))
                .andExpect(header().doesNotExist("Retry-After"));

        assertThat(countOf(jdbc, "personalization_screening", seller.id())).as("the refused order was not analysed or stored")
                .isEqualTo(1);
        assertThat(used(seller)).isEqualTo(quota);
    }

    @Test
    void theUsageOfAClientListsThePackItsPlanHas() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);

        mvc.perform(get("/api/v1/usage").header(HEADER, seller.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.packs[?(@ == '" + Pack.PERSONALIZATION_SCREENING.id() + "')]").exists());
        mvc.perform(get("/api/v1/usage").header(HEADER, free.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.packs[?(@ == '" + Pack.PERSONALIZATION_SCREENING.id() + "')]").doesNotExist());
    }

    @Test
    void anIncompleteResultIsStoredButNotChargedAndTheQuotaIsStillCheckedFirst() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        PersonalizationScreeningService incomplete = new PersonalizationScreeningService(marketplaces, normalizer,
                failingRulesets(properties, resources, versions, transactions), trademarkRuntime, store, transactions, plans);

        PersonalizationScreeningResult result = incomplete.check(seller.id(),
                MAPPER.treeToValue(order(), PersonalizationScreeningRequest.class));

        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(result.failedRuleIds()).containsExactly("failing_rule_for_tests");
        assertThat(countOf(jdbc, "personalization_screening", seller.id())).isEqualTo(1);
        assertThat(used(seller)).as("INCOMPLETE is not charged").isZero();

        // whether a result will be complete is not known before the analysis, so the quota is checked first
        setUsed(jdbc, seller.id(), NOVEMBER, quota(jdbc, PlanCode.SELLER));
        assertThatThrownBy(() -> incomplete.check(seller.id(),
                MAPPER.treeToValue(order(), PersonalizationScreeningRequest.class))).isInstanceOf(QuotaExceededException.class);
        assertThat(countOf(jdbc, "personalization_screening", seller.id())).isEqualTo(1);
    }
}
