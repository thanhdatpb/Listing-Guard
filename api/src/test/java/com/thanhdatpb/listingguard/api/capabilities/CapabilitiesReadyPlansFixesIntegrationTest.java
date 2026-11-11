package com.thanhdatpb.listingguard.api.capabilities;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.countOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.quota;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Catalog ready, plans on and fixes on: the answer says so, it is the same for a client of any plan, and reading it costs no
 * unit even far past the quota (ADR-013) and stores nothing.
 */
@SpringBootTest(properties = {"listing-guard.plans.enabled=true", "listing-guard.fixes.enabled=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class CapabilitiesReadyPlansFixesIntegrationTest {

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

    private String raw(String key) throws Exception {
        return mvc.perform(get("/api/v1/capabilities").header(HEADER, key)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void aReadyCatalogPlansAndFixesAreReportedWithoutTheCatalogName() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);
        String raw = raw(free.key());
        JsonNode body = mapper.readTree(raw);

        assertThat(body.at("/trademarkCatalog/state").asText()).isEqualTo("READY");
        assertThat(body.at("/trademarkCatalog/demo").asBoolean()).isFalse();
        assertThat(body.at("/plans/enforced").asBoolean()).isTrue();
        assertThat(body.at("/fixes/enabled").asBoolean()).isTrue();
        body.get("packs").forEach(p -> assertThat(p.get("available").asBoolean()).as(p.get("id").asText()).isTrue());
        assertThat(raw).doesNotContain(TrademarkReadyTestConfig.catalogVersion());
    }

    @Test
    void theAnswerDoesNotDependOnTheClientsPlanAndCostsNoUnit() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);
        TestClient pro = newClient(provisioning, clients, PlanCode.PRO);
        int quota = quota(jdbc, PlanCode.FREE);

        String expected = raw(pro.key());
        for (int i = 0; i < quota + 5; i++) {
            assertThat(raw(free.key())).isEqualTo(expected);
        }

        assertThat(countOf(jdbc, "client_usage", free.id())).as("nothing is counted").isZero();
        assertThat(countOf(jdbc, "client_usage", pro.id())).isZero();
        assertThat(countOf(jdbc, "listing_check", free.id())).as("nothing is stored").isZero();
        mvc.perform(get("/api/v1/usage").header(HEADER, free.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(0));
    }
}
