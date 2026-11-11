package com.thanhdatpb.listingguard.api.image;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.additional;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.main;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.notAnImage;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.product;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.request;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.requestWith;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.countOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.quota;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.setUsed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.image.ImageApiSupport.Part;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Plans and the monthly quota on the image endpoint (ADR-013, ADR-008), with plans on: the pack comes first, then the cache,
 * then the quota, and one unit is one new COMPLETE result. The quotas are read from the {@code plan} table. The test-only
 * rulesets are used, because the shipped ones have no threshold and so give no complete result.
 */
@SpringBootTest(properties = {
        "listing-guard.plans.enabled=true",
        "listing-guard.image.enabled=true",
        "listing-guard.image.rulesets.AMAZON=classpath:image/amazon-image-test.yaml",
        "listing-guard.image.rulesets.ETSY=classpath:image/etsy-image-test.yaml"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PlanTestClockConfig.class})
class ImageCheckQuotaIntegrationTest {

    private static final AtomicInteger NEXT = new AtomicInteger(500);

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

    @BeforeEach
    void startInTheMiddleOfNovember() {
        clock.set(PlanTestSupport.NOVEMBER_15);
    }

    private static List<Part> complete() {
        return List.of(main(product(NEXT.incrementAndGet(), 300, "PNG", 0)));
    }

    private ResultActions perform(TestClient client, MockMultipartHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HEADER, client.key()));
    }

    private JsonNode ok(TestClient client, List<Part> parts) throws Exception {
        return MAPPER.readTree(perform(client, request("AMAZON", parts)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private int used(TestClient client) {
        return PlanTestSupport.used(jdbc, client.id(), NOVEMBER);
    }

    @Test
    void aPlanWithoutThePackIsRefusedBeforeAnythingElseEvenBeforeTheRequestIsRead() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);

        perform(free, request("AMAZON", complete())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("plan-not-allowed")).andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.pack").value(Pack.IMAGE_CHECKS.id()));
        perform(free, requestWith("not json", complete())).andExpect(status().isForbidden());

        assertThat(countOf(jdbc, "image_check", free.id())).as("nothing is stored for a refused request").isZero();
        assertThat(used(free)).isZero();
    }

    @Test
    void aNewCompleteResultCostsOneUnitAndTheSameRequestAgainCostsNothing() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        List<Part> parts = complete();

        JsonNode first = ok(seller, parts);
        assertThat(first.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(used(seller)).isEqualTo(1);

        JsonNode again = ok(seller, parts);
        assertThat(again.get("cached").asBoolean()).isTrue();
        assertThat(used(seller)).as("a cached answer is free").isEqualTo(1);
    }

    @Test
    void anIncompleteResultIsStoredButNeverCharged() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        List<Part> parts = List.of(main(product(NEXT.incrementAndGet(), 300, "PNG", 0)), additional(notAnImage()));

        JsonNode result = ok(seller, parts);

        assertThat(result.get("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(used(seller)).isZero();
        assertThat(countOf(jdbc, "image_check", seller.id())).isEqualTo(1);
    }

    @Test
    void atTheLimitANewResultIs429WithoutRetryAfterAndAStoredOneIsStillAnswered() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        List<Part> paid = complete();
        ok(seller, paid);
        int quota = quota(jdbc, PlanCode.SELLER);
        setUsed(jdbc, seller.id(), NOVEMBER, quota);

        perform(seller, request("AMAZON", complete())).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("quota-exceeded")).andExpect(jsonPath("$.plan").value("SELLER"))
                .andExpect(jsonPath("$.quota").value(quota)).andExpect(jsonPath("$.used").value(quota))
                .andExpect(header().doesNotExist("Retry-After"));
        assertThat(ok(seller, paid).get("cached").asBoolean()).isTrue();
        assertThat(used(seller)).isEqualTo(quota);
    }

    @Test
    void theRequestIsStillValidatedBeforeTheQuotaIsConsulted() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        setUsed(jdbc, seller.id(), NOVEMBER, quota(jdbc, PlanCode.SELLER));

        perform(seller, requestWith("not json", complete())).andExpect(status().isBadRequest());
    }
}
