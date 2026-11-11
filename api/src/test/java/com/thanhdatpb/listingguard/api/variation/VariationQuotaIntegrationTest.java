package com.thanhdatpb.listingguard.api.variation;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.countOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.quota;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.setUsed;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.amazonFamily;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.failingRulesets;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.unique;
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
import com.thanhdatpb.listingguard.variation.Completeness;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plans and the monthly quota on the variation endpoint (ADR-013, ADR-015), with plans on: the pack comes first, then the
 * cache, then the quota, and one unit is one new COMPLETE result. The quotas are read from the {@code plan} table.
 */
@SpringBootTest(properties = "listing-guard.plans.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PlanTestClockConfig.class})
class VariationQuotaIntegrationTest {

    private static final String VARIATION = "/api/v1/variation-checks";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    VariationRulesets rulesets;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    MutableClock clock;

    @Autowired
    PlanService plans;

    @Autowired
    VariationProperties properties;

    @Autowired
    ResourceLoader resources;

    @Autowired
    RulesetVersionRepository versions;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    NormalizerRegistry marketplaces;

    @Autowired
    VariationNormalizer normalizer;

    @Autowired
    VariationCheckStore store;

    @BeforeEach
    void startInTheMiddleOfNovember() {
        clock.set(PlanTestSupport.NOVEMBER_15);
    }

    private ResultActions perform(TestClient client, JsonNode body) throws Exception {
        return mvc.perform(post(VARIATION).header(HEADER, client.key()).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body)));
    }

    private JsonNode ok(TestClient client, JsonNode body) throws Exception {
        return MAPPER.readTree(perform(client, body).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private int used(TestClient client) {
        return PlanTestSupport.used(jdbc, client.id(), NOVEMBER);
    }

    private JsonNode family() {
        return amazonFamily(rulesets.ruleset(), unique(), null);
    }

    @Test
    void aPlanWithoutThePackIsRefusedBeforeAnythingElse() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);

        perform(free, family()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan-not-allowed"))
                .andExpect(jsonPath("$.plan").value("FREE")).andExpect(jsonPath("$.pack").value(Pack.VARIATION_CHECKS.id()));

        assertThat(countOf(jdbc, "variation_check", free.id())).as("nothing is stored for a refused request").isZero();
        assertThat(used(free)).isZero();
    }

    @Test
    void sellerAndProMayUseThePackAndOneNewResultCostsOneUnitACachedOneNothing() throws Exception {
        for (PlanCode plan : new PlanCode[]{PlanCode.SELLER, PlanCode.PRO}) {
            TestClient client = newClient(provisioning, clients, plan);
            JsonNode body = family();

            assertThat(ok(client, body).get("cached").asBoolean()).isFalse();
            assertThat(used(client)).as(plan + " after a new result").isEqualTo(1);
            assertThat(ok(client, body).get("cached").asBoolean()).isTrue();
            assertThat(used(client)).as(plan + " after a cached one").isEqualTo(1);
        }
    }

    @Test
    void aPlanThatLosesThePackIsRefusedAndThePackIsNotRememberedPerClient() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        jdbc.update("DELETE FROM plan_pack WHERE plan_code = 'SELLER' AND pack_id = ?", Pack.VARIATION_CHECKS.id());
        try {
            perform(seller, family()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan-not-allowed"));
            assertThat(used(seller)).isZero();
        } finally {
            jdbc.update("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('SELLER', ?) ON CONFLICT DO NOTHING",
                    Pack.VARIATION_CHECKS.id());
        }
        ok(seller, family());
    }

    @Test
    void atTheLimitACachedAnswerIsStillGivenAndANewFamilyIsRefusedWithoutRetryAfter() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        JsonNode known = family();
        ok(seller, known);
        int quota = quota(jdbc, PlanCode.SELLER);
        setUsed(jdbc, seller.id(), NOVEMBER, quota);

        assertThat(ok(seller, known).get("cached").asBoolean()).as("already paid for").isTrue();
        perform(seller, family()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("quota-exceeded")).andExpect(jsonPath("$.plan").value("SELLER"))
                .andExpect(jsonPath("$.quota").value(quota)).andExpect(jsonPath("$.used").value(quota))
                .andExpect(jsonPath("$.resetAt").value("2026-12-01T00:00:00Z"))
                .andExpect(header().doesNotExist("Retry-After"));

        assertThat(countOf(jdbc, "variation_check", seller.id())).as("the refused family was not analysed or stored").isEqualTo(1);
        assertThat(used(seller)).isEqualTo(quota);
    }

    @Test
    void theUsageOfAClientListsThePackItsPlanHas() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);

        mvc.perform(get("/api/v1/usage").header(HEADER, seller.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.packs[?(@ == '" + Pack.VARIATION_CHECKS.id() + "')]").exists());
        mvc.perform(get("/api/v1/usage").header(HEADER, free.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.packs[?(@ == '" + Pack.VARIATION_CHECKS.id() + "')]").doesNotExist());
    }

    @Test
    void anIncompleteResultIsStoredButNotChargedAndTheQuotaIsStillCheckedFirst() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        VariationCheckService incomplete = new VariationCheckService(marketplaces, normalizer,
                failingRulesets(properties, resources, versions, transactions), store, transactions, plans);
        VariationCheckRequest request = MAPPER.treeToValue(family(), VariationCheckRequest.class);

        VariationCheckResult result = incomplete.check(seller.id(), request);

        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(countOf(jdbc, "variation_check", seller.id())).isEqualTo(1);
        assertThat(used(seller)).as("INCOMPLETE is not charged").isZero();

        // whether a result will be complete is not known before the analysis, so the quota is checked first
        setUsed(jdbc, seller.id(), NOVEMBER, quota(jdbc, PlanCode.SELLER));
        assertThatThrownBy(() -> incomplete.check(seller.id(), MAPPER.treeToValue(family(), VariationCheckRequest.class)))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(countOf(jdbc, "variation_check", seller.id())).isEqualTo(1);
    }
}
