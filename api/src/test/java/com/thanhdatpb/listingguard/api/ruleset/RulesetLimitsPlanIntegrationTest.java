package com.thanhdatpb.listingguard.api.ruleset;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.countOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.quota;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Plans on (ADR-013): reading the limits is not a result that is stored, so it costs no unit and a client over its quota can
 * still read it. The context has the same settings as {@code PlanQuotaApiIntegrationTest}, so it is shared.
 */
@SpringBootTest(properties = "listing-guard.plans.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class RulesetLimitsPlanIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Test
    void readingTheLimitsCostsNoUnitEvenFarPastTheQuota() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);
        int quota = quota(jdbc, PlanCode.FREE);

        for (int i = 0; i < quota + 5; i++) {
            mvc.perform(get("/api/v1/rulesets/ETSY/limits").header(HEADER, free.key())).andExpect(status().isOk());
            mvc.perform(get("/api/v1/rulesets/AMAZON/limits").header(HEADER, free.key())).andExpect(status().isOk());
        }

        assertThat(countOf(jdbc, "client_usage", free.id())).as("nothing is counted").isZero();
        assertThat(countOf(jdbc, "listing_check", free.id())).as("nothing is stored").isZero();
        mvc.perform(get("/api/v1/usage").header(HEADER, free.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(0));
    }
}
