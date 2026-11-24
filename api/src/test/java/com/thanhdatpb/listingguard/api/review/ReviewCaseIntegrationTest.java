package com.thanhdatpb.listingguard.api.review;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.body;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "listing-guard.review-cases.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class ReviewCaseIntegrationTest {

    private static final String CASES = "/api/v1/review-cases";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClientProvisioning provisioning;
    @Autowired private ClientRepository clients;

    @Test
    void grantsAreExplicitAndCaseAndFindingLookupsAreTenantScoped() throws Exception {
        TestClient owner = client();
        TestClient other = client();
        String checkId = check(owner, "/api/v1/checks", "Synthetic " + unique());
        mvc.perform(get(CASES)).andExpect(status().isUnauthorized());
        create(owner, "listing-rules", checkId, 0).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("review-write-forbidden"));
        UUID actor = grant(owner);
        grant(other);
        create(other, "listing-rules", checkId, 0).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("finding-not-found"));
        create(owner, "listing-rules", checkId, 999).andExpect(status().isNotFound());
        String id = acceptedCase(owner, "listing-rules", checkId, 0);
        assertThat(acceptedCase(owner, "listing-rules", checkId, 0)).isEqualTo(id);
        mvc.perform(get(CASES + "/" + id).header(HEADER, owner.key())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.reviewCase.status").value("OPEN"))
                .andExpect(jsonPath("$.evidenceAvailable").value(true));
        mvc.perform(get(CASES).header(HEADER, other.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(CASES + "/" + id).header(HEADER, other.key())).andExpect(status().isNotFound());
        mvc.perform(get(CASES + "/" + id + "/events").header(HEADER, other.key())).andExpect(status().isNotFound());
        decide(other, id, decision(UUID.randomUUID(), 0, "IN_REVIEW", null))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("review-case-not-found"));

        jdbc.update("DELETE FROM review_case_writer WHERE api_key_id = ?", actor);
        decide(owner, id, decision(UUID.randomUUID(), 0, "IN_REVIEW", null)).andExpect(status().isForbidden());
        mvc.perform(get(CASES + "/" + id + "/events").header(HEADER, owner.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].actorId").value(actor.toString()));
    }

    @Test
    void decisionsValidateTransitionsAndKeepExactlyOneEventPerRevisionWithoutListingText() throws Exception {
        TestClient client = client();
        UUID actor = grant(client);
        String title = "Synthetic private marker " + unique();
        String checkId = check(client, "/api/v1/checks", title);
        String id = acceptedCase(client, "listing-rules", checkId, 0);
        decide(client, id, decision(UUID.randomUUID(), 0, "RESOLVED", "FIX_CONFIRMED"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("review-invalid-transition"));
        UUID requestId = UUID.randomUUID();
        var start = decision(requestId, 0, "IN_REVIEW", null);
        start.put("reviewerId", UUID.randomUUID().toString());
        String first = read(decide(client, id, start).andExpect(status().isOk())
                .andExpect(jsonPath("$.actorId").value(actor.toString()))).toString();
        decide(client, id, decision(UUID.randomUUID(), 1, "RESOLVED", "NO_ACTION_REQUIRED"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("review-invalid-reason"));
        decide(client, id, decision(UUID.randomUUID(), 1, "RESOLVED", "FIX_CONFIRMED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
        assertThat(read(decide(client, id, start).andExpect(status().isOk())).toString()).isEqualTo(first);
        decide(client, id, decision(requestId, 0, "DISMISSED", "NO_ACTION_REQUIRED")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("review-request-conflict"));
        decide(client, id, decision(UUID.randomUUID(), 2, "IN_REVIEW", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("review-invalid-transition"));

        String eventsPath = CASES + "/" + id + "/events";
        mvc.perform(get(eventsPath).header(HEADER, client.key()).param("limit", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[1].status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.nextAfterRevision").value(1));
        mvc.perform(get(eventsPath).header(HEADER, client.key()).param("afterRevision", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("RESOLVED"));
        assertThat(jdbc.queryForList("SELECT * FROM review_case_event WHERE client_id = ?", client.id()).toString())
                .doesNotContain(title, client.key(), client.name());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finding_verdict WHERE client_id = ?", Integer.class, client.id()))
                .isZero();
    }

    @Test
    void keysetPaginationHasNoDuplicatesWhenTimestampsTieAndBindsFilters() throws Exception {
        TestClient client = client();
        grant(client);
        String checkId = check(client, "/api/v1/checks", "Synthetic " + unique());
        String first = acceptedCase(client, "listing-rules", checkId, 0);
        String second = acceptedCase(client, "listing-rules", checkId, 1);
        jdbc.update("UPDATE review_case SET created_at = '2026-11-01T00:00:00Z' WHERE client_id = ?", client.id());
        JsonNode page = read(mvc.perform(get(CASES).header(HEADER, client.key()).param("status", "OPEN")
                .param("marketplace", "ETSY").param("sort", "OLDEST").param("limit", "1")
                .param("createdAfter", "2026-11-01T00:00:00Z")).andExpect(status().isOk()));
        String cursor = page.path("nextCursor").asText();
        JsonNode next = read(mvc.perform(get(CASES).header(HEADER, client.key()).param("status", "OPEN")
                .param("marketplace", "ETSY").param("sort", "OLDEST").param("limit", "1")
                .param("createdAfter", "2026-11-01T00:00:00Z").param("cursor", cursor)).andExpect(status().isOk()));
        assertThat(java.util.List.of(page.at("/items/0/id").asText(), next.at("/items/0/id").asText()))
                .containsExactlyInAnyOrder(first, second);
        mvc.perform(get(CASES).header(HEADER, client.key()).param("cursor", cursor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("review-invalid-page"));
        mvc.perform(get(CASES).header(HEADER, client.key()).param("marketplace", "AMAZON"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(CASES).header(HEADER, client.key()).param("severity", "HIGH"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(CASES).header(HEADER, client.key()).param("createdBefore", "2026-11-01T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void concurrentDecisionsCannotBothUpdateTheSameRevision() throws Exception {
        TestClient client = client();
        grant(client);
        String id = acceptedCase(client, "listing-rules", check(client, "/api/v1/checks", "Synthetic " + unique()), 0);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> write = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The concurrent request did not start.");
                }
                return decide(client, id, decision(UUID.randomUUID(), 0, "IN_REVIEW", null))
                        .andReturn().getResponse().getStatus();
            };
            var first = pool.submit(write);
            var second = pool.submit(write);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        mvc.perform(get(CASES + "/" + id + "/events").header(HEADER, client.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void anExpiredCheckKeepsOnlyCaseMetadataAndCannotBeDecided() throws Exception {
        TestClient client = client();
        grant(client);
        String checkId = check(client, "/api/v1/trademark-checks", "Zorvex " + unique());
        String id = acceptedCase(client, "trademark-risk", checkId, 0);
        OffsetDateTime expired = OffsetDateTime.now().minusDays(1);
        jdbc.update("UPDATE trademark_check SET created_at = ?, expires_at = ? WHERE client_id = ? AND id = ?",
                expired.minusDays(1), expired, client.id(), UUID.fromString(checkId));
        mvc.perform(get(CASES + "/" + id).header(HEADER, client.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceAvailable").value(false));
        decide(client, id, decision(UUID.randomUUID(), 0, "IN_REVIEW", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("review-evidence-unavailable"));
    }

    @Test
    void invalidBodiesAndQueryValuesReturnProblems() throws Exception {
        TestClient client = client();
        grant(client);
        create(client, "unknown", UUID.randomUUID().toString(), 0).andExpect(status().isBadRequest());
        create(client, "listing-rules", UUID.randomUUID().toString(), -1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
        mvc.perform(get(CASES).header(HEADER, client.key()).param("sort", "severity DESC"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"));
        mvc.perform(get(CASES).header(HEADER, client.key()).param("limit", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("review-invalid-page"));
    }

    private TestClient client() {
        return newClient(provisioning, clients, PlanCode.PRO);
    }

    private UUID grant(TestClient client) {
        return jdbc.queryForObject("""
                INSERT INTO review_case_writer (api_key_id) SELECT id FROM api_key WHERE client_id = ?
                RETURNING api_key_id
                """, UUID.class, client.id());
    }

    private String check(TestClient client, String endpoint, String title) throws Exception {
        return read(mvc.perform(post(endpoint).header(HEADER, client.key()).contentType(MediaType.APPLICATION_JSON)
                .content(body(title))).andExpect(status().isOk())).path("checkId").asText();
    }

    private ResultActions create(TestClient client, String pack, String checkId, int index) throws Exception {
        return mvc.perform(post(CASES).header(HEADER, client.key()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("pack", pack).put("checkId", checkId)
                        .put("findingIndex", index).toString()));
    }

    private String acceptedCase(TestClient client, String pack, String checkId, int index) throws Exception {
        return read(create(client, pack, checkId, index).andExpect(status().isOk())).path("id").asText();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode decision(UUID id, int revision, String state, String reason) {
        return mapper.createObjectNode().put("requestId", id.toString()).put("expectedRevision", revision)
                .put("status", state).put("reason", reason);
    }

    private ResultActions decide(TestClient client, String id, JsonNode decision) throws Exception {
        return mvc.perform(post(CASES + "/" + id + "/decisions").header(HEADER, client.key())
                .contentType(MediaType.APPLICATION_JSON).content(decision.toString()));
    }

    private JsonNode read(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
