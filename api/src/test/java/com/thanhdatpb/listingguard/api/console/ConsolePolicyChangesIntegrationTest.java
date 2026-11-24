package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.policymonitor.PolicyDiffEngine;
import com.thanhdatpb.listingguard.api.policymonitor.PolicyImpact;
import com.thanhdatpb.listingguard.api.policymonitor.PolicyImpactReports;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test", "spring.mail.host=mail.example.test"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class ConsolePolicyChangesIntegrationTest {
    private static final String ROOT = "/console/policy-changes";
    private static final String SOURCE = "https://www.etsy.com/legal/sellers/";
    private static final String OLD = "a".repeat(64);
    private static final String NEW = "b".repeat(64);
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PolicyImpactReports reports;
    @Autowired private ClientProvisioning provisioning;
    @Autowired private ClientRepository clients;
    @MockitoBean private JavaMailSender mail;

    @Test
    void persistsApprovedImpactAndIsolatesDecisionsWhileReusingReaudit() throws Exception {
        jdbc.update("DELETE FROM policy_impact_report");
        var rulesets = jdbc.queryForList("SELECT id, version FROM ruleset_version ORDER BY id");
        ConsoleUser manager = account("ADMIN");
        ConsoleUser member = account("MEMBER");
        var change = new PolicyDiffEngine.DocumentChange("VERSION_CHANGED", "ETSY", SOURCE, "Synthetic policy",
                "2026-11-01", "2026-11-18", OLD, NEW, true, List.of(
                new PolicyDiffEngine.SectionChange("SECTION_CHANGED", "  =1+1", OLD, NEW, "Private excerpt placeholder")));
        var run = new PolicyDiffEngine.RunFile(Instant.parse("2026-11-24T01:00:00Z"), true, List.of(change), List.of(),
                List.of(new PolicyDiffEngine.RuleReview("ETSY", "sample-version", "sample_rule", "secondary", SOURCE, null, "SampleType")));
        var approved = Map.of(SOURCE, new PolicyImpact.Approval(OLD, NEW, LocalDate.of(2026, 11, 20)));
        reports.publish(run, Map.of());
        reports.publish(run, approved);
        reports.publish(run, approved);
        assertThat(reports.list(manager.clientId(), "ETSY", null, null, null)).hasSize(2);
        var id = reports.list(manager.clientId(), "ETSY", PolicyImpact.Status.PROPOSED, null, null).getFirst().id();
        String url = ROOT + "/" + id;
        mvc.perform(get(ROOT)).andExpect(status().is3xxRedirection());
        assertThat(html(mvc.perform(get(ROOT).with(signedIn(member)).param("marketplace", "AMAZON"))))
                .contains("No policy change reports");
        assertThat(html(mvc.perform(get(ROOT).with(signedIn(member)).param("from", "2026-11-21"))))
                .doesNotContain("Synthetic policy");
        assertThat(html(mvc.perform(get(url).with(signedIn(manager)).param("lang", "vi"))))
                .contains("Tác động đề xuất", "2026-11-20", "sample_rule", "SampleType", "/console/reaudit?marketplace=ETSY")
                .doesNotContain("Private excerpt placeholder");
        assertThat(html(mvc.perform(get(url + "/report.csv").with(signedIn(manager))))).contains("'  =1+1");
        mvc.perform(post(url + "/decision").with(signedIn(manager)).param("decision", "RULE_CHANGE").param("confirmed", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(post(url + "/decision").with(signedIn(member)).with(csrf()).param("decision", "RULE_CHANGE").param("confirmed", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(post(url + "/decision").with(signedIn(manager)).with(csrf()).param("decision", "RULE_CHANGE").param("confirmed", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(reports.get(manager.clientId(), id).orElseThrow().status()).isEqualTo(PolicyImpact.Status.RULE_CHANGE);
        assertThat(reports.get(member.clientId(), id).orElseThrow().status()).isEqualTo(PolicyImpact.Status.PROPOSED);
        assertThat(reports.get(manager.clientId(), id).orElseThrow().sourceStatus()).isEqualTo(PolicyImpact.Status.PROPOSED);

        assertThat(jdbc.queryForList("SELECT id, version FROM ruleset_version ORDER BY id")).isEqualTo(rulesets);
        long checks = jdbc.queryForObject("SELECT count(*) FROM listing_check WHERE client_id = ?", Long.class, manager.clientId());
        mvc.perform(get("/console/reaudit").with(signedIn(manager)).param("marketplace", "ETSY")).andExpect(status().isOk());
        mvc.perform(multipart("/console/reaudit/run").with(signedIn(manager)).with(csrf()).param("marketplace", "ETSY"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_check WHERE client_id = ?", Long.class, manager.clientId()))
                .isEqualTo(checks);
        byte[] upload = """
                SKU,TITLE,DESCRIPTION,PRICE,QUANTITY,TAGS,MATERIALS
                FIXTURE-1,Synthetic mug,A synthetic mug.,19.99,5,"mug,ceramic",clay
                """.getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart("/console/reaudit/run").file(new MockMultipartFile("file", "fixture.csv", "text/csv", upload))
                        .with(signedIn(manager)).with(csrf()).param("marketplace", "ETSY"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_check WHERE client_id = ?", Long.class, manager.clientId()))
                .isGreaterThan(checks);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_check WHERE client_id = ?", Long.class, member.clientId()))
                .isZero();
    }

    private ConsoleUser account(String role) {
        var user = userOf(newClient(provisioning, clients, PlanCode.PRO));
        jdbc.update("INSERT INTO account(email, client_id, role) VALUES (?, ?, ?)", user.email(), user.clientId(), role);
        return user;
    }

    private static String html(ResultActions result) throws Exception {
        return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
