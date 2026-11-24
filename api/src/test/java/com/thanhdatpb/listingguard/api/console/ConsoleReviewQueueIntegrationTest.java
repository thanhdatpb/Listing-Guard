package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.body;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "listing-guard.review-cases.enabled=true",
        "listing-guard.verified-cases.enabled=true",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class ConsoleReviewQueueIntegrationTest {
    private static final String QUEUE = "/console/review-queue";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClientProvisioning provisioning;
    @Autowired private ClientRepository clients;
    @MockitoBean private JavaMailSender mailSender;

    @Test
    void decisionsRequireAnExplicitGrantAndConfirmationAndReplayAsTheAuthenticatedAccount() throws Exception {
        TestClient tenant = client();
        ConsoleUser user = userOf(tenant);
        UUID actor = insertAccount(user, "ADMIN");
        String id = createCase(tenant, "/api/v1/checks", "listing-rules", "Synthetic sample mug");
        String page = QUEUE + "/" + id;
        UUID requestId = UUID.randomUUID();

        mvc.perform(get(QUEUE)).andExpect(status().is3xxRedirection());
        Document readOnly = html(mvc.perform(get(page).with(signedIn(user)).param("lang", "vi")));
        assertThat(readOnly.select("form[action$=/decisions]")).isEmpty();
        assertThat(readOnly.text()).contains("Cần được cấp quyền reviewer");
        mvc.perform(decision(user, page, requestId, 0, "IN_REVIEW").with(csrf()).param("confirmed", "true"))
                .andExpect(status().isForbidden());

        jdbc.update("INSERT INTO console_review_grant (account_id, client_id) VALUES (?, ?)", actor, tenant.id());
        Document form = html(mvc.perform(get(page).with(signedIn(user)).param("lang", "en")));
        assertThat(form.select("form[action$=/decisions] input[name=_csrf]")).hasSize(1);
        assertThat(form.select("label[for=review-status], input[name=confirmed][required]")).hasSize(2);
        assertThat(form.text()).contains("They do not certify legal compliance.");
        mvc.perform(decision(user, page, requestId, 0, "IN_REVIEW").param("confirmed", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(decision(user, page, requestId, 0, "IN_REVIEW").with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(decision(user, page, requestId, 0, "RESOLVED").with(csrf()).param("confirmed", "true"))
                .andExpect(status().isConflict());

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(decision(user, page, requestId, 0, "IN_REVIEW").with(csrf())
                            .param("confirmed", "true").param("reviewerId", UUID.randomUUID().toString()))
                    .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", page));
        }
        assertThat(jdbc.queryForList("""
                SELECT actor_id, actor_kind FROM review_case_event WHERE client_id = ? ORDER BY revision
                """, tenant.id())).hasSize(2).last().satisfies(event -> {
            assertThat(event).containsEntry("actor_id", actor).containsEntry("actor_kind", "CONSOLE_ACCOUNT");
        });
        mvc.perform(decision(user, page, UUID.randomUUID(), 1, "RESOLVED").with(csrf()).param("confirmed", "true"))
                .andExpect(status().is3xxRedirection());
        Document resolved = html(mvc.perform(get(page).with(signedIn(user)).param("lang", "en")));
        assertThat(resolved.text()).contains("Resolved");
        assertThat(resolved.select("form[action$=/decisions]")).isEmpty();
        jdbc.update("DELETE FROM console_review_grant WHERE account_id = ?", actor);
        mvc.perform(decision(user, page, UUID.randomUUID(), 1, "RESOLVED").with(csrf()).param("confirmed", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredAndCrossTenantEvidenceIsUnavailableAndRawDataIsNeverRendered() throws Exception {
        TestClient tenant = client();
        ConsoleUser owner = userOf(tenant);
        TestClient other = client();
        ConsoleUser outsider = userOf(other);
        insertAccount(owner, "MEMBER");
        UUID otherActor = insertAccount(outsider, "MEMBER");
        jdbc.update("INSERT INTO console_review_grant (account_id, client_id) VALUES (?, ?)", otherActor, other.id());
        String title = "Zorvex synthetic-private-marker";
        String id = createCase(tenant, "/api/v1/trademark-checks", "trademark-risk", title);
        String page = QUEUE + "/" + id;
        String evidence = page + "/evidence";

        Document available = html(mvc.perform(get(evidence).with(signedIn(owner)).param("lang", "en")));
        assertThat(available.text()).contains("Retained evidence", "Analysis version").doesNotContain(title);
        mvc.perform(get(page).with(signedIn(outsider))).andExpect(status().isNotFound());
        mvc.perform(get(evidence).with(signedIn(outsider))).andExpect(status().isNotFound());
        mvc.perform(decision(outsider, page, UUID.randomUUID(), 0, "IN_REVIEW").with(csrf()).param("confirmed", "true"))
                .andExpect(status().isNotFound());

        jdbc.update("""
                UPDATE trademark_check SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day'
                WHERE client_id = ?
                """, tenant.id());
        assertThat(html(mvc.perform(get(page).with(signedIn(owner)))).select("a[href$=/evidence]")).isEmpty();
        mvc.perform(get(evidence).with(signedIn(owner))).andExpect(status().isNotFound());
    }

    @Test
    void filtersAndPaginationKeepTiedCasesDistinctWhileTotalsRemainTenantScoped() throws Exception {
        TestClient tenant = client();
        ConsoleUser user = userOf(tenant);
        insertAccount(user, "MEMBER");
        OffsetDateTime recent = OffsetDateTime.now().minusDays(1);
        List<String> included = new ArrayList<>();
        for (int index = 0; index < 26; index++) {
            included.add(seedCase(tenant, "ETSY", "HIGH", recent));
        }
        seedCase(tenant, "AMAZON", "HIGH", recent);
        seedCase(tenant, "ETSY", "LOW", recent);
        seedCase(tenant, "ETSY", "HIGH", recent.minusDays(20));
        seedCase(client(), "ETSY", "HIGH", recent);

        Document first = html(mvc.perform(get(QUEUE).with(signedIn(user)).param("lang", "en")
                .param("marketplace", "ETSY").param("severity", "HIGH").param("status", "OPEN").param("age", "WEEK")));
        assertThat(first.select("[data-case-id]")).hasSize(25);
        assertThat(first.select("#review-open-count").text()).isEqualTo("29");
        String next = first.select("#review-next").attr("href");
        Document second = html(mvc.perform(get(java.net.URI.create(next)).with(signedIn(user)).param("lang", "en")));
        assertThat(second.select("[data-case-id]")).hasSize(1);
        assertThat(second.select("#review-next")).isEmpty();
        List<String> actual = new ArrayList<>(first.select("[data-case-id]").eachAttr("data-case-id"));
        actual.addAll(second.select("[data-case-id]").eachAttr("data-case-id"));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(included);
        assertThat(html(mvc.perform(get(QUEUE).with(signedIn(user)).param("status", "RESOLVED").param("lang", "en"))).text())
                .contains("No review cases match these filters.");
        mvc.perform(get(QUEUE).with(signedIn(user)).param("age", "invalid")).andExpect(status().isBadRequest());
    }

    @Test
    void verificationIsExplicitTenantScopedAndReplaySafeWithAnAudit(CapturedOutput output) throws Exception {
        TestClient tenant = client();
        ConsoleUser reviewer = userOf(tenant);
        ConsoleUser reader = userOf(tenant);
        UUID actor = grant(reviewer);
        insertAccount(reader, "ADMIN");
        ConsoleUser outsider = userOf(client());
        grant(outsider);
        String privateTitle = "Synthetic private fixture marker";
        String id = createCase(tenant, "/api/v1/checks", "listing-rules", privateTitle);
        String page = QUEUE + "/" + id;
        UUID caseId = UUID.fromString(id);

        mvc.perform(verifiedAction(outsider, id, "").with(csrf())).andExpect(status().isNotFound());
        mvc.perform(verifiedAction(reader, id, "").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(verifiedAction(reviewer, id, "")).andExpect(status().isForbidden());
        mvc.perform(verifiedAction(reviewer, id, "").with(csrf())).andExpect(status().isConflict());
        mvc.perform(verifiedAction(reviewer, UUID.randomUUID().toString(), "").with(csrf())).andExpect(status().isNotFound());

        String checkId = jdbc.queryForObject("SELECT check_id::text FROM review_case WHERE client_id = ? AND id = ?",
                String.class, tenant.id(), caseId);
        mvc.perform(post("/api/v1/feedback").header(HEADER, tenant.key()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("pack", "listing-rules").put("checkId", checkId)
                        .put("findingIndex", 0).put("verdict", "FALSE_POSITIVE").put("note", privateTitle).toString()))
                .andExpect(status().isOk());
        resolve(reviewer, id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verified_case WHERE client_id = ?", Integer.class, tenant.id())).isZero();

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(verifiedAction(reviewer, id, "").with(csrf()).param("reviewerId", UUID.randomUUID().toString()))
                    .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", page));
        }
        Document localized = html(mvc.perform(get(page).with(signedIn(reviewer)).param("lang", "vi")));
        assertThat(localized.text()).contains("Case tương tự đã xác minh").doesNotContain(privateTitle);
        assertThat(localized.select("[aria-label=Lịch sử xác minh] li")).hasSize(1);
        assertThat(html(mvc.perform(get(page).with(signedIn(reader)))).select("#verified-heading")).isEmpty();
        mvc.perform(verifiedAction(outsider, id, "/revoke").with(csrf())).andExpect(status().isNotFound());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(verifiedAction(reviewer, id, "/revoke").with(csrf())).andExpect(status().is3xxRedirection());
        }
        var events = jdbc.queryForList("SELECT action, actor_id FROM verified_case_event WHERE client_id = ? ORDER BY revision", tenant.id());
        assertThat(events).hasSize(2).allSatisfy(event -> assertThat(event).containsEntry("actor_id", actor));
        assertThat(events).extracting(event -> event.get("action")).containsExactly("VERIFIED", "REVOKED");
        assertThat(jdbc.queryForMap("SELECT * FROM verified_case WHERE client_id = ?", tenant.id()))
                .containsEntry("severity", jdbc.queryForObject("SELECT severity FROM review_case WHERE client_id = ? AND id = ?",
                        String.class, tenant.id(), caseId)).containsEntry("reason", "FIX_CONFIRMED");
        assertThat(output.getAll()).doesNotContain(privateTitle);
    }

    @Test
    void labeledSimilarityFixtureMeasuresRankAndExcludesOtherTenantsRevokedExpiredAndWrongRule() throws Exception {
        TestClient tenant = client();
        ConsoleUser reviewer = userOf(tenant);
        grant(reviewer);
        String target = createCase(tenant, "/api/v1/checks", "listing-rules", "Synthetic query mug");
        Document empty = html(mvc.perform(get(QUEUE + "/" + target).with(signedIn(reviewer)).param("lang", "en")));
        assertThat(empty.text()).contains("No similar verified cases are available.");

        List<String> relevant = new ArrayList<>();
        List<String> differentVersion = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            String id = verifiedReference(tenant, reviewer, index);
            if (index < 2) {
                relevant.add(id);
            } else {
                differentVersion.add(id);
                jdbc.update("UPDATE verified_case SET analysis_version='fixture-earlier-version' WHERE client_id=? AND case_id=?",
                        tenant.id(), UUID.fromString(id));
            }
        }
        String revoked = verifiedReference(tenant, reviewer, 7);
        mvc.perform(verifiedAction(reviewer, revoked, "/revoke").with(csrf())).andExpect(status().is3xxRedirection());
        String expired = verifiedReference(tenant, reviewer, 8);
        jdbc.update("""
                UPDATE verified_case SET verified_at = now() - interval '2 days', expires_at = now() - interval '1 day'
                WHERE client_id = ? AND case_id = ?
                """, tenant.id(), UUID.fromString(expired));
        String differentRule = verifiedReference(tenant, reviewer, 9);
        jdbc.update("UPDATE verified_case SET rule_id = 'fixture-different-rule' WHERE client_id = ? AND case_id = ?",
                tenant.id(), UUID.fromString(differentRule));
        TestClient other = client();
        ConsoleUser outsider = userOf(other);
        grant(outsider);
        String foreign = verifiedReference(other, outsider, 10);

        long started = System.nanoTime();
        Document result = html(mvc.perform(get(QUEUE + "/" + target).with(signedIn(reviewer)).param("lang", "en")));
        double elapsed = (System.nanoTime() - started) / 1_000_000.0;
        List<String> matches = result.select("[data-reference-id]").eachAttr("data-reference-id");
        assertThat(matches).hasSize(5).doesNotContain(revoked, expired, differentRule, foreign, target);
        assertThat(matches.subList(0, 2)).containsExactlyInAnyOrderElementsOf(relevant);
        assertThat(matches.subList(2, 5)).allMatch(differentVersion::contains);
        assertThat(result.text()).contains("Analysis or source version differs.");
        long hits = matches.stream().filter(relevant::contains).count();
        double recall = (double) hits / relevant.size();
        double precision = (double) hits / matches.size();
        assertThat(recall).isEqualTo(1.0);
        assertThat(precision).isEqualTo(0.4);
        System.out.printf(java.util.Locale.ROOT, "Verified-case fixture Recall@5=%.3f Precision@5=%.3f elapsed_ms=%.3f%n",
                recall, precision, elapsed);
    }

    private UUID grant(ConsoleUser user) {
        UUID actor = insertAccount(user, "MEMBER");
        jdbc.update("INSERT INTO console_review_grant (account_id, client_id) VALUES (?, ?)", actor, user.clientId());
        return actor;
    }

    private void resolve(ConsoleUser user, String id) throws Exception {
        String page = QUEUE + "/" + id;
        mvc.perform(decision(user, page, UUID.randomUUID(), 0, "IN_REVIEW").with(csrf()).param("confirmed", "true"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(decision(user, page, UUID.randomUUID(), 1, "RESOLVED").with(csrf()).param("confirmed", "true"))
                .andExpect(status().is3xxRedirection());
    }

    private String verifiedReference(TestClient tenant, ConsoleUser user, int index) throws Exception {
        String id = createCase(tenant, "/api/v1/checks", "listing-rules", "Synthetic reference mug " + index);
        resolve(user, id);
        mvc.perform(verifiedAction(user, id, "").with(csrf())).andExpect(status().is3xxRedirection());
        return id;
    }

    private MockHttpServletRequestBuilder verifiedAction(ConsoleUser user, String id, String suffix) {
        return post(QUEUE + "/" + id + "/verified" + suffix).with(signedIn(user)).param("confirmed", "true");
    }

    private TestClient client() {
        return newClient(provisioning, clients, PlanCode.PRO);
    }

    private UUID insertAccount(ConsoleUser user, String role) {
        return jdbc.queryForObject("INSERT INTO account(email, client_id, role) VALUES (?, ?, ?) RETURNING id",
                UUID.class, user.email(), user.clientId(), role);
    }

    private String seedCase(TestClient tenant, String marketplace, String severity, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO review_case (client_id, id, pack_id, check_id, finding_position, marketplace_code, severity,
                                         status, revision, created_at)
                VALUES (?, ?, 'listing-rules', ?, 0, ?, ?, 'OPEN', 0, ?)
                """, tenant.id(), id, UUID.randomUUID(), marketplace, severity, createdAt);
        return id.toString();
    }

    private String createCase(TestClient tenant, String endpoint, String pack, String title) throws Exception {
        jdbc.update("INSERT INTO review_case_writer (api_key_id) SELECT id FROM api_key WHERE client_id = ? ON CONFLICT DO NOTHING", tenant.id());
        String check = json(mvc.perform(post(endpoint).header(HEADER, tenant.key()).contentType(MediaType.APPLICATION_JSON)
                .content(body(title)))).path("checkId").asText();
        return json(mvc.perform(post("/api/v1/review-cases").header(HEADER, tenant.key()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("pack", pack).put("checkId", check).put("findingIndex", 0).toString())))
                .path("id").asText();
    }

    private MockHttpServletRequestBuilder decision(ConsoleUser user, String page, UUID requestId, int revision, String state) {
        return post(page + "/decisions").with(signedIn(user)).param("requestId", requestId.toString())
                .param("revision", Integer.toString(revision)).param("status", state);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Document html(ResultActions actions) throws Exception {
        return Jsoup.parse(actions.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
