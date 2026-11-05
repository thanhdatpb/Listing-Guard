package com.thanhdatpb.listingguard.api.plan;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.body;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.countOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.quota;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The default configuration (plans off, ADR-013): a deployment that hosts the code for itself is unchanged. A FREE client
 * uses a pack its plan does not include and passes its quota, nothing is counted, and {@code GET /usage} says that nothing
 * is enforced. Also holds the pack ids to the ids the rulesets declare.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class PlansOffIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    PlanService plans;

    @Autowired
    TrademarkRulesets trademarkRulesets;

    @Test
    void plansAreOffByDefault() {
        assertThat(plans.enabled()).isFalse();
        PlanService.Admission admission = plans.requirePack(UUID.randomUUID(), Pack.TRADEMARK_RISK);
        assertThat(admission.enforced()).as("no client lookup, no refusal").isFalse();
    }

    @Test
    void aFreeClientUsesAPackOutsideItsPlanAndPassesItsQuotaAndNothingIsCounted() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);
        int quota = quota(jdbc, PlanCode.FREE);

        mvc.perform(post("/api/v1/trademark-checks").header(HEADER, free.key()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Plain ceramic mug " + unique())))
                .andExpect(status().isOk());
        for (int i = 0; i < quota + 3; i++) {
            mvc.perform(post("/api/v1/checks").header(HEADER, free.key()).contentType(MediaType.APPLICATION_JSON)
                            .content(body("Plain ceramic mug " + unique())))
                    .andExpect(status().isOk());
        }

        assertThat(countOf(jdbc, "listing_check", free.id())).isEqualTo(quota + 3);
        assertThat(countOf(jdbc, "client_usage", free.id())).as("nothing is counted while plans are off").isZero();
    }

    @Test
    void usageSaysNothingIsEnforced() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);

        mvc.perform(get("/api/v1/usage").header(HEADER, free.key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforced").value(false))
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.monthlyQuota").doesNotExist())
                .andExpect(jsonPath("$.remaining").doesNotExist())
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.periodStart").isNotEmpty())
                .andExpect(jsonPath("$.resetAt").isNotEmpty())
                .andExpect(jsonPath("$.packs", Matchers.contains("batch", "listing-rules", "policy-analyzer", "sheet-connector",
                        "trademark-risk")));
    }

    @Test
    void thePackIdsAreTheIdsOfTheRulesetsThatDeclareThem() {
        assertThat(Pack.LISTING_RULES.id()).isEqualTo(RulesetConfig.DEFAULT_PACK_ID);
        assertThat(Pack.TRADEMARK_RISK.id()).isEqualTo(trademarkRulesets.ruleset().packId());
    }

    @Test
    void aNewClientIsFreeAndThePlanCodesParseIgnoringCase() {
        TestClient created = newClient(provisioning, clients, PlanCode.FREE);
        assertThat(clients.findByName(created.name()).orElseThrow().getPlanCode()).isEqualTo("FREE");
        assertThat(PlanCode.parse(" seller ")).contains(PlanCode.SELLER);
        assertThat(PlanCode.parse("Pro")).contains(PlanCode.PRO);
        assertThat(PlanCode.parse("gold")).isEmpty();
        assertThat(PlanCode.parse(null)).isEmpty();
    }
}
