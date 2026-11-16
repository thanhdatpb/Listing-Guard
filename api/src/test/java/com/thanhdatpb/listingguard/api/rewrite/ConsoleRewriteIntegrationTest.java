package com.thanhdatpb.listingguard.api.rewrite;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.section;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.rewrite.StubLlm.answer;
import static com.thanhdatpb.listingguard.api.rewrite.StubLlm.candidate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The "Gợi ý sửa" button and its panel in the console editor, the page without a script, and the count of copies (ADR-037). The
 * model is a stub; the checks are the real ones.
 */
@SpringBootTest(properties = {"listing-guard.console.enabled=true", "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test", "spring.mail.host=mail.example.test",
        "listing-guard.llm.rewrite.enabled=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, RewriteTestConfig.class})
class ConsoleRewriteIntegrationTest {

    private static final String TOO_LONG = "shirt " + "long ".repeat(35).strip();

    @Autowired MockMvc mvc;
    @Autowired StubLlm llm;
    @Autowired ClientProvisioning provisioning;
    @Autowired ClientRepository clients;
    @Autowired ConsoleAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mailSender;

    @BeforeEach
    void reset() {
        llm.reset();
    }

    private ConsoleUser seller() {
        TestClient client = newClient(provisioning, clients, PlanCode.PRO);
        String email = "rewrite-" + UUID.randomUUID() + "@example.test";
        accounts.saveAndFlush(new ConsoleAccount(email, client.id(), ConsoleRole.MEMBER));
        return new ConsoleUser(email, client.id());
    }

    private MockHttpServletRequestBuilder rewritePost(ConsoleUser user, Map<String, String> form, boolean htmx) {
        MockHttpServletRequestBuilder request = post("/console/rewrite/editor").with(signedIn(user)).with(csrf());
        form.forEach(request::param);
        return htmx ? request.header("HX-Request", "true") : request;
    }

    private String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void theEditorShowsTheButtonOnlyForAListingWithAnErrorAndCarriesTheFindingItStartsFrom() throws Exception {
        ConsoleUser seller = seller();

        String broken = body(mvc.perform(checkPost(seller, etsyForm(TOO_LONG))));
        String clean = body(mvc.perform(checkPost(seller, etsyForm("Plain cotton mug"))));

        String rewrite = section(broken, "rewrite");
        assertThat(rewrite).contains("action=\"/console/rewrite/editor\"").contains("name=\"title\"").contains("name=\"tags\"")
                .contains("name=\"pack\" value=\"listing-rules\"").contains("name=\"checkId\" value=\"");
        assertThat(broken).contains("/console/rewrite.js").contains("/console/rewrite.css");
        assertThat(clean).doesNotContain("/console/rewrite/editor").doesNotContain("id=\"rewrite\"");
    }

    @Test
    void aSafeSuggestionIsShownWithACopyButtonAndNothingThatAppliesIt() throws Exception {
        ConsoleUser seller = seller();
        llm.reply(answer(candidate("Plain cotton shirt")));

        String panel = body(mvc.perform(rewritePost(seller, etsyForm(TOO_LONG), true)));

        assertThat(panel).contains("id=\"rewrite-panel\"").contains("Option 1").contains("Plain cotton shirt")
                .contains("data-copy-text=\"Plain cotton shirt\"").contains("action=\"/console/rewrite/used\"")
                .contains("not legal clearance");
        assertThat(panel).doesNotContain("/console/check/apply").doesNotContain("name=\"apply\"");
        assertThat(panel).doesNotContain("<html");
    }

    @Test
    void withoutAScriptTheAnswerIsAPageOfItsOwnWithALinkBack() throws Exception {
        ConsoleUser seller = seller();
        llm.reply(answer(candidate("Plain cotton shirt")));

        String page = body(mvc.perform(rewritePost(seller, etsyForm(TOO_LONG), false)));

        assertThat(page).contains("<html").contains("Rewrite suggestions").contains("Plain cotton shirt")
                .contains("href=\"/console/check?marketplace=ETSY\"");
    }

    @Test
    void whenNothingPassesThePageSaysThereIsNoSafeSuggestionAndShowsNoText() throws Exception {
        ConsoleUser seller = seller();
        llm.reply(answer(candidate(TOO_LONG), candidate(TOO_LONG + " extra")));

        String panel = body(mvc.perform(rewritePost(seller, etsyForm(TOO_LONG), true)));

        assertThat(panel).contains("No safe suggestion").doesNotContain("data-copy-text").doesNotContain(TOO_LONG + " extra");
    }

    @Test
    void anUnavailableModelIsSaidSoAndNothingIsInvented() throws Exception {
        ConsoleUser seller = seller();
        llm.reply("not json");

        String panel = body(mvc.perform(rewritePost(seller, etsyForm(TOO_LONG), true)));

        assertThat(panel).contains("Suggestions are not available").doesNotContain("data-copy-text").doesNotContain("Option 1");
    }

    @Test
    void aFormThatCannotBeReadIsRefusedWithoutEchoingIt() throws Exception {
        ConsoleUser seller = seller();
        Map<String, String> form = etsyForm("Secret title 123");
        form.put("price", "not a number");

        String refusal = mvc.perform(rewritePost(seller, form, true)).andExpect(status().isBadRequest()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(refusal).doesNotContain("Secret title 123");
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void aCopyIsRecordedOnceAndAnotherClientCannotRecordIt() throws Exception {
        ConsoleUser seller = seller();
        ConsoleUser stranger = seller();
        llm.reply(answer(candidate("Plain cotton shirt")));
        String panel = body(mvc.perform(rewritePost(seller, etsyForm(TOO_LONG), true)));
        Matcher run = Pattern.compile("name=\"runId\" value=\"([0-9a-f-]{36})\"").matcher(panel);
        assertThat(run.find()).isTrue();
        String runId = run.group(1);

        mvc.perform(post("/console/rewrite/used").with(signedIn(seller)).with(csrf()).param("runId", runId).param("candidate", "1"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/console/rewrite/used").with(signedIn(seller)).with(csrf()).param("runId", runId).param("candidate", "1"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/console/rewrite/used").with(signedIn(seller)).with(csrf()).param("runId", runId).param("candidate", "2"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/console/rewrite/used").with(signedIn(stranger)).with(csrf()).param("runId", runId).param("candidate", "1"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/console/rewrite/used").with(signedIn(seller)).with(csrf()).param("runId", "garbage").param("candidate", "1"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/console/rewrite/used").with(signedIn(seller)).param("runId", runId).param("candidate", "1"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM rewrite_suggestion_run WHERE id = ?::uuid AND used_candidate = 1",
                Integer.class, runId)).isEqualTo(1);
        String stats = body(mvc.perform(get("/console/stats").with(signedIn(seller))));
        assertThat(section(stats, "stats-rewrite")).contains("Use rate").contains("100");
    }
}
