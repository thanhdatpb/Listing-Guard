package com.thanhdatpb.listingguard.api.rewrite;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.console.ConsoleTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The feature is off by default (ADR-037): with the setting left alone there is no bean of it, no route and no button, even when a
 * provider is configured, and the statistics page has no section for it.
 */
@SpringBootTest(properties = {"listing-guard.console.enabled=true", "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test", "spring.mail.host=mail.example.test"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, RewriteTestConfig.class})
class RewriteOffIntegrationTest {

    private static final String TOO_LONG = "shirt " + "long ".repeat(35).strip();

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired ClientProvisioning provisioning;
    @Autowired ClientRepository clients;
    @Autowired StubLlm llm;
    @MockitoBean JavaMailSender mailSender;

    private ConsoleUser seller() {
        return ConsoleTestSupport.userOf(newClient(provisioning, clients, PlanCode.PRO));
    }

    @Test
    void withTheSettingLeftAloneNothingOfTheFeatureExists() {
        assertThat(context.getBeansOfType(ListingRewriter.class)).isEmpty();
        assertThat(context.getBeansOfType(RewriteRunStore.class)).isEmpty();
        assertThat(context.getBeansOfType(ListingEvaluator.class)).isEmpty();
        assertThat(context.getBeansOfType(com.thanhdatpb.listingguard.api.console.editor.ConsoleRewriteController.class)).isEmpty();
    }

    @Test
    void theEditorOfAListingWithAnErrorHasNoButtonAndNoScriptOfTheFeature() throws Exception {
        String page = mvc.perform(checkPost(seller(), etsyForm(TOO_LONG))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(page).contains("title_length");
        assertThat(page).doesNotContain("/console/rewrite").doesNotContain("id=\"rewrite\"").doesNotContain("rewrite.js");
    }

    @Test
    void theRoutesAreNotThereAndTheModelIsNeverCalled() throws Exception {
        ConsoleUser seller = seller();

        mvc.perform(post("/console/rewrite/editor").with(signedIn(seller)).with(csrf()).param("marketplace", "ETSY")
                .param("title", TOO_LONG)).andExpect(status().isNotFound());
        mvc.perform(post("/console/rewrite/bulk").with(signedIn(seller)).with(csrf()).param("marketplace", "ETSY")
                .param("listing", "{}")).andExpect(status().isNotFound());
        mvc.perform(post("/console/rewrite/used").with(signedIn(seller)).with(csrf()).param("runId", "x").param("candidate", "1"))
                .andExpect(status().isNotFound());

        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void theStatisticsPageHasNoSectionForIt() throws Exception {
        String page = mvc.perform(get("/console/stats").with(signedIn(seller()))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(page).doesNotContain("stats-rewrite");
    }
}
