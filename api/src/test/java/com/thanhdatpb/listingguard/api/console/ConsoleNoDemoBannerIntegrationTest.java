package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The default deployment, with no trademark catalog configured, and the shared bar: no banner and no demo notice anywhere, and
 * the bar links the pages of the console in the order of the plan.
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
class ConsoleNoDemoBannerIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @MockitoBean
    JavaMailSender mailSender;

    private ConsoleUser user() {
        return userOf(PlanTestSupport.newClient(provisioning, clients, PlanCode.FREE));
    }

    private static void assertNoDemo(String html) {
        assertThat(html).doesNotContain("demo-banner").doesNotContain("Demo catalog").doesNotContain("trademark-demo-note");
    }

    @Test
    void withoutADemoCatalogNoPageShowsTheBanner() throws Exception {
        ConsoleUser user = user();

        assertNoDemo(mvc.perform(get("/login")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertNoDemo(mvc.perform(get("/login/verify").param("token", "not-a-token")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertNoDemo(mvc.perform(get("/console").with(signedIn(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertNoDemo(mvc.perform(get("/console/check").with(signedIn(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertNoDemo(mvc.perform(checkPost(user, etsyForm("Mug " + unique()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void theBarLinksTheConsolePagesInOrderAndMarksTheCurrentOne() throws Exception {
        ConsoleUser user = user();

        String dashboard = mvc.perform(get("/console").with(signedIn(user))).andReturn().getResponse().getContentAsString();
        String check = mvc.perform(get("/console/check").with(signedIn(user))).andReturn().getResponse().getContentAsString();

        for (String html : new String[] {dashboard, check}) {
            assertThat(html.indexOf(">Dashboard<")).isLessThan(html.indexOf(">Check listing<"));
            assertThat(html.indexOf(">Check listing<")).isLessThan(html.indexOf(">API keys<"));
            assertThat(html.indexOf(">API keys<")).isLessThan(html.indexOf("Sign out"));
            assertThat(html).contains("action=\"/logout\"");
        }
        assertThat(dashboard).containsOnlyOnce("aria-current=\"page\"");
        assertThat(dashboard).containsPattern("aria-current=\"page\"[^>]*>Dashboard<");
        // the marketplace tabs of the editor also use aria-current, so only the links of the bar are looked at
        assertThat(check).containsPattern("aria-current=\"page\"[^>]*>Check listing<");
        assertThat(check).doesNotContainPattern("aria-current=\"page\"[^>]*>(Dashboard|API keys)<");
    }
}
