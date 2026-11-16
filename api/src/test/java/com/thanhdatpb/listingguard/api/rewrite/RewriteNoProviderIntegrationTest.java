package com.thanhdatpb.listingguard.api.rewrite;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.llm.LlmClient;
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
 * The setting is on but the shared LLM client of ADR-036 is off (its default): the feature has nothing to ask, so the console shows
 * no button, and a press that somehow arrives is answered as unavailable without any text being sent anywhere.
 */
@SpringBootTest(properties = {"listing-guard.console.enabled=true", "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test", "spring.mail.host=mail.example.test",
        "listing-guard.llm.rewrite.enabled=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class RewriteNoProviderIntegrationTest {

    private static final String TOO_LONG = "shirt " + "long ".repeat(35).strip();

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired ListingRewriter rewriter;
    @Autowired ClientProvisioning provisioning;
    @Autowired ClientRepository clients;
    @MockitoBean JavaMailSender mailSender;

    @Test
    void withoutAProviderThereIsNoButtonAndAPressIsUnavailable() throws Exception {
        var user = ConsoleTestSupport.userOf(newClient(provisioning, clients, PlanCode.PRO));

        assertThat(context.getBean(LlmClient.class).enabled()).isFalse();
        assertThat(rewriter.available()).isFalse();
        String page = mvc.perform(checkPost(user, etsyForm(TOO_LONG))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("title_length").doesNotContain("/console/rewrite").doesNotContain("id=\"rewrite\"");
        assertThat(rewriter.suggest(user.clientId(), null, "ETSY", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode().put("title", TOO_LONG), null).status()).isEqualTo(RewriteOutcome.Status.UNAVAILABLE);
    }
}
