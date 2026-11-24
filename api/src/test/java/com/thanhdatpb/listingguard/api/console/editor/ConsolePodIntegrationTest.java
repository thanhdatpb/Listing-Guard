package com.thanhdatpb.listingguard.api.console.editor;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
        "listing-guard.plans.enabled=true",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class ConsolePodIntegrationTest {
    private static final String POD = "/console/pod-readiness";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClientProvisioning provisioning;
    @Autowired private ClientRepository clients;
    @MockitoBean private JavaMailSender mailSender;

    @Test
    void profilesAreBilingualAndInputsWorkByKeyboardWithoutScripts() throws Exception {
        ConsoleUser user = user();
        mvc.perform(get(POD)).andExpect(status().is3xxRedirection());
        Document english = html(mvc.perform(get(POD).with(signedIn(user)).param("lang", "en")));
        assertThat(english.select("#pod-draft input[name=_csrf]")).hasSize(1);
        assertThat(english.select("#pod-draft label[for=profileKey], #pod-draft label[for=variants], meta[name=viewport]"))
                .hasSize(3);
        assertThat(english.select("#pod-draft [tabindex]")).isEmpty();
        assertThat(english.text()).contains("T-shirt", "Personalized ornament", "Run a check to see the three results");
        assertThat(html(mvc.perform(get(POD).with(signedIn(user)).param("lang", "vi"))).text())
                .contains("Mức sẵn sàng POD", "Áo thun", "Đồ trang trí cá nhân hóa");
        mvc.perform(draft(user, "ETSY", "t-shirt-v1")).andExpect(status().isForbidden());
    }

    @Test
    void apparelLeavesProductionIncompleteWhileItFindsDuplicateSkuAndTrademarkRisk(CapturedOutput output) throws Exception {
        ConsoleUser user = user();
        String hostile = "<img src=x onerror=alert(42)>";
        Document result = html(mvc.perform(draft(user, "ETSY", "t-shirt-v1").with(csrf())
                .param("title", "Zorvex cotton tee").param("description", hostile)
                .param("variants", "SHIRT-1 | M | Blue\nSHIRT-1 | L | Black")
                .param("artworkWidth", "12").param("artworkHeight", "20"))
                .andExpect(header().string("Cache-Control", "no-store")));
        assertThat(result.select("#pod-marketplace-status, #pod-ip-status, #pod-production-status")).hasSize(3);
        assertThat(result.selectFirst("#pod-ip-status").attr("data-status")).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.selectFirst("#pod-production-status").attr("data-status")).isEqualTo("INCOMPLETE");
        assertThat(result.selectFirst("#description").text()).isEqualTo(hostile);
        assertThat(result.select("img[onerror]")).isEmpty();
        assertThat(result.text()).contains("duplicate_sku", "Print limits are unverified").doesNotContain("production-ready");
        assertThat(result.select("form[action$=/review]")).isEmpty();
        assertThat(output.getAll()).doesNotContain(hostile);
    }

    @Test
    void ornamentScreensOnlyGeneratedSampleAndMissingProfileDoesNotRunAnyCheck() throws Exception {
        ConsoleUser user = user();
        Document missing = html(mvc.perform(draft(user, "AMAZON", "unreleased").with(csrf())));
        assertThat(missing.text()).contains("Select an available profile");
        assertThat(countChecks(user)).isZero();

        Document unconfigured = html(mvc.perform(draft(user, "AMAZON", "personalized-ornament-v1").with(csrf())
                .param("variants", "ORN-1").param("artworkWidth", "10").param("artworkHeight", "10")));
        assertThat(unconfigured.text()).contains("Configure the text personalization field").doesNotContain("Fixed sample screened");
        Document screened = html(mvc.perform(draft(user, "AMAZON", "personalized-ornament-v1").with(csrf())
                .param("personalizationConfigured", "true").param("personalization", "discarded-private-buyer-field")
                .param("variants", "ORN-1").param("artworkWidth", "10").param("artworkHeight", "10")));
        assertThat(screened.selectFirst("#pod-production-status").attr("data-status")).isEqualTo("INCOMPLETE");
        assertThat(screened.text()).contains("Fixed sample screened").doesNotContain("discarded-private-buyer-field");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personalization_screening WHERE client_id = ?",
                Long.class, user.clientId())).isZero();
    }

    @Test
    void invalidMeasurementsKeepDraftAndApplyingFixRequiresAnExplicitRecheck() throws Exception {
        ConsoleUser user = user();
        Document invalid = Jsoup.parse(mvc.perform(draft(user, "ETSY", "t-shirt-v1").with(csrf())
                        .param("title", "Synthetic tee").param("artworkWidth", "-10"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(invalid.selectFirst("input#title").val()).isEqualTo("Synthetic tee");
        assertThat(invalid.select("[role=alert]")).isNotEmpty();
        assertThat(countChecks(user)).isZero();

        String longTag = "a very very long tag name";
        Document checked = html(mvc.perform(draft(user, "ETSY", "t-shirt-v1").with(csrf())
                .param("title", "Synthetic tee").param("tags", longTag)));
        Element fix = checked.selectFirst("button[formaction$=/apply]");
        assertThat(fix).isNotNull();
        MockHttpServletRequestBuilder apply = post(POD + "/apply").with(signedIn(user)).with(csrf())
                .param("marketplace", "ETSY").param("profileKey", "t-shirt-v1").param("tags", longTag)
                .param("title", "Synthetic tee").param("apply", fix.val()).param("lang", "en");
        for (Element field : checked.select("input[name^='fix." + fix.val() + ".']")) {
            apply.param(field.attr("name"), field.val());
        }
        Document changed = html(mvc.perform(apply));
        assertThat(changed.select("#pod-results")).isEmpty();
        assertThat(changed.text()).contains("Draft unchecked");
        String tags = changed.selectFirst("textarea#tags").val();
        assertThat(tags).isNotEqualTo(longTag);
        assertThat(countChecks(user)).isEqualTo(1);
        assertThat(html(mvc.perform(draft(user, "ETSY", "t-shirt-v1").with(csrf())
                .param("title", "Synthetic tee").param("tags", tags).param("recheck", "true")))
                .select("#pod-results")).hasSize(1);
    }

    @Test
    void reviewCreationAndSignOffReuseTenantScopeAuditAndCompletionMetrics() throws Exception {
        ConsoleUser reviewer = user();
        UUID reviewerId = grant(reviewer);
        ConsoleUser outsider = user();
        grant(outsider);
        Document report = html(mvc.perform(draft(reviewer, "ETSY", "t-shirt-v1").with(csrf())
                .param("variants", "duplicate | M | Red\nduplicate | L | Blue")));
        Element production = report.selectFirst("section[aria-labelledby=pod-production]");
        Element review = production.selectFirst("form[action$=/review]");
        assertThat(review).isNotNull();
        mvc.perform(open(review, outsider).with(csrf()).param("confirmed", "true")).andExpect(status().isNotFound());
        ConsoleUser readOnly = new ConsoleUser("readonly@example.test", reviewer.clientId());
        mvc.perform(open(review, readOnly).with(csrf()).param("confirmed", "true")).andExpect(status().isForbidden());
        mvc.perform(open(review, reviewer).param("confirmed", "true")).andExpect(status().isForbidden());
        mvc.perform(open(review, reviewer).with(csrf())).andExpect(status().isBadRequest());

        String location = mvc.perform(open(review, reviewer).with(csrf()).param("confirmed", "true")
                        .param("reviewerId", UUID.randomUUID().toString()))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        mvc.perform(open(review, reviewer).with(csrf()).param("confirmed", "true"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", location));
        mvc.perform(get(location).with(signedIn(outsider))).andExpect(status().isNotFound());
        mvc.perform(decide(location, reviewer, "IN_REVIEW", "0")).andExpect(status().is3xxRedirection());
        mvc.perform(decide(location, reviewer, "RESOLVED", "1")).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForList("SELECT actor_id, actor_kind FROM review_case_event WHERE client_id = ?", reviewer.clientId()))
                .hasSize(3).allSatisfy(event -> assertThat(event).containsEntry("actor_id", reviewerId)
                        .containsEntry("actor_kind", "CONSOLE_ACCOUNT"));
        assertThat(html(mvc.perform(get(POD).with(signedIn(reviewer)).param("lang", "en"))).text())
                .contains("1 of 1 retained cases", "across 1 resolved cases");
        assertThat(html(mvc.perform(get(POD).with(signedIn(outsider)).param("lang", "en"))).text())
                .contains("0 of 0 retained cases");
    }

    @Test
    void packRestrictionsAndAbsentFieldsKeepTheirOwnDimensionsIncomplete() throws Exception {
        ConsoleUser free = userOf(newClient(provisioning, clients, PlanCode.FREE));
        Document report = html(mvc.perform(draft(free, "ETSY", "t-shirt-v1").with(csrf())
                .param("variants", "SHIRT-1")));
        assertThat(report.selectFirst("#pod-ip-status").attr("data-status")).isEqualTo("INCOMPLETE");
        assertThat(report.selectFirst("#pod-production-status").attr("data-status")).isEqualTo("INCOMPLETE");
        assertThat(report.text()).contains("Enter a size for each T-shirt SKU.", "Enter a color for each T-shirt SKU.",
                "Your plan does not include variation checks.", "Enter both artwork dimensions.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM variation_check WHERE client_id = ?",
                Long.class, free.clientId())).isZero();
    }

    private static MockHttpServletRequestBuilder draft(ConsoleUser user, String marketplace, String profile) {
        return post(POD).with(signedIn(user)).param("lang", "en")
                .param("marketplace", marketplace).param("profileKey", profile);
    }

    private static MockHttpServletRequestBuilder open(Element form, ConsoleUser user) {
        return post(POD + "/review").with(signedIn(user)).param("pack", form.selectFirst("input[name=pack]").val())
                .param("checkId", form.selectFirst("input[name=checkId]").val())
                .param("position", form.selectFirst("input[name=position]").val());
    }

    private static MockHttpServletRequestBuilder decide(String location, ConsoleUser user, String status, String revision) {
        return post(location + "/decisions").with(signedIn(user)).with(csrf()).param("status", status)
                .param("revision", revision).param("requestId", UUID.randomUUID().toString()).param("confirmed", "true");
    }

    private ConsoleUser user() {
        return userOf(newClient(provisioning, clients, PlanCode.SELLER));
    }

    private UUID grant(ConsoleUser user) {
        UUID accountId = jdbc.queryForObject("""
                INSERT INTO account (client_id, email, role) VALUES (?, ?, 'MEMBER') RETURNING id
                """, UUID.class, user.clientId(), user.email());
        jdbc.update("INSERT INTO console_review_grant (account_id, client_id) VALUES (?, ?)", accountId, user.clientId());
        return accountId;
    }

    private long countChecks(ConsoleUser user) {
        return jdbc.queryForObject("SELECT count(*) FROM listing_check WHERE client_id = ?", Long.class, user.clientId());
    }

    private static Document html(ResultActions result) throws Exception {
        return Jsoup.parse(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
