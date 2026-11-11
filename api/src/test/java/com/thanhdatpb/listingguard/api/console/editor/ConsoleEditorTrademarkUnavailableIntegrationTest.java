package com.thanhdatpb.listingguard.api.console.editor;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.section;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRun;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The editor in a deployment with plans off and no trademark catalog loaded (the default): the trademark panel says it is
 * unavailable and the rule results are still shown; an incomplete rule run has no score; and an editor result does not change
 * what the API and the batch job see while {@code listing-guard.fixes.enabled} is off.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleEditorTrademarkUnavailableIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    TrademarkRulesets trademarkRulesets;

    @MockitoBean
    JavaMailSender mailSender;

    @MockitoSpyBean
    RuleEngine ruleEngine;

    private TestClient client() {
        return PlanTestSupport.newClient(provisioning, clients, PlanCode.FREE);
    }

    private String press(ConsoleUser user, Map<String, String> form) throws Exception {
        return mvc.perform(checkPost(user, form)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aCatalogThatIsNotLoadedIsSaidAndTheRuleResultsAreStillShown() throws Exception {
        Map<String, String> form = etsyForm("Plain mug " + unique());
        form.put("tags", "a very very long tag name");

        String html = press(userOf(client()), form);

        String trademark = section(html, "trademark-panel");
        assertThat(trademark).contains("Trademark check unavailable").contains("<p class=\"big\">Unavailable</p>")
                .contains(trademarkRulesets.ruleset().disclaimer());
        assertThat(section(html, "readiness-panel")).containsPattern("<p class=\"big\">\\d+</p>");
        assertThat(section(html, "rule-findings")).contains("tag_length");
        assertThat(html).doesNotContain("id=\"trademark-findings\"");
    }

    @Test
    void whenPlansAreOffThePageSaysNothingIsCounted() throws Exception {
        String html = mvc.perform(get("/console/check").with(signedIn(userOf(client()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Plan limits are not enforced in this deployment").doesNotContain("Cost: up to");
    }

    @Test
    void anIncompleteRuleRunHasNoScoreAndTheTrademarkPanelIsStillThere() throws Exception {
        doReturn(new RuleRun("listing-rules", List.of(), List.of("synthetic_rule")))
                .when(ruleEngine).runDetailed(any(Listing.class), any(RulesetConfig.class));

        String html = press(userOf(client()), etsyForm("Incomplete mug " + unique()));

        String readiness = section(html, "readiness-panel");
        assertThat(readiness).contains("<p class=\"big\">Not scored</p>").doesNotContain("Ready").doesNotContain("Not ready");
        assertThat(html).contains("verdict incomplete");
        assertThat(section(html, "trademark-panel")).contains("Trademark check unavailable");
    }

    @Test
    void theEditorDoesNotChangeWhatTheApiShowsWhileTheFlagIsOff() throws Exception {
        TestClient client = client();
        String title = "Handmade ceramic coffee mug ".repeat(8).trim();
        Map<String, String> form = etsyForm(title);
        form.put("description", "A synthetic mug " + unique());

        String html = press(userOf(client), form);
        // the editor computed a verified fix for the title that is over the limit
        assertThat(section(html, "rule-findings")).contains("Suggested fix:");

        String body = mapper.writeValueAsString(Map.of("marketplace", "ETSY", "listing", Map.of(
                "title", title, "description", form.get("description"), "tags", List.of("mug", "ceramic"),
                "price", "19.99", "quantity", 5, "category", "Home", "who_made", "i_did", "when_made", "made_to_order")));
        JsonNode api = mapper.readTree(mvc.perform(post("/api/v1/checks").header("X-API-Key", client.key())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        // the same stored result, shown in the old shape: no readiness, no fixesComputed, no fix
        assertThat(api.get("cached").asBoolean()).isTrue();
        assertThat(api.has("fixesComputed")).isFalse();
        assertThat(api.has("readiness")).isFalse();
        for (JsonNode finding : api.get("findings")) {
            assertThat(finding.has("fix")).isFalse();
        }
    }
}
