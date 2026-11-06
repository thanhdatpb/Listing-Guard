package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.section;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
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
 * A deployment whose trademark catalog version starts with {@code demo-} (the version is never imported here, so the catalog is
 * refused; the banner depends on the configured version only): every console page, signed in or not, carries the banner, and the
 * trademark panel of the editor says it again.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test",
        "listing-guard.trademark.catalog-version=demo-fictional-2026-11-0123456789ab"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleDemoBannerIntegrationTest {

    static final String TEXT = "Demo catalog: fictional marks only. Real trademark checking is not active.";

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

    private static void assertOneBanner(String html) {
        assertThat(html).containsOnlyOnce("id=\"demo-banner\"").contains(TEXT);
    }

    @Test
    void theSignInPagesCarryTheBannerBeforeAnyoneIsSignedIn() throws Exception {
        String login = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String verify = mvc.perform(get("/login/verify").param("token", "not-a-token")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertOneBanner(login);
        assertOneBanner(verify);
    }

    @Test
    void theDashboardAndTheEditorFormCarryTheBanner() throws Exception {
        ConsoleUser user = user();

        String dashboard = mvc.perform(get("/console").with(signedIn(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String form = mvc.perform(get("/console/check").with(signedIn(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertOneBanner(dashboard);
        assertOneBanner(form);
        assertThat(form).doesNotContain("trademark-demo-note");
    }

    @Test
    void theKeysPagesCarryTheBannerToo() throws Exception {
        TestClient client = PlanTestSupport.newClient(provisioning, clients, PlanCode.FREE);
        ConsoleUser user = userOf(client);
        String keyId = provisioning.activeKeys(client.id()).get(0).getKeyId();

        String list = mvc.perform(get("/console/api-keys").with(signedIn(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String confirm = mvc.perform(get("/console/api-keys/" + keyId + "/revoke").with(signedIn(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String created = mvc.perform(post("/console/api-keys").with(signedIn(user)).with(csrf()).param("label", "demo"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertOneBanner(list);
        assertOneBanner(confirm);
        assertOneBanner(created);
    }

    @Test
    void theTrademarkPanelOfAResultRepeatsTheNoticeBesideTheBanner() throws Exception {
        String html = mvc.perform(checkPost(user(), etsyForm("Mug " + unique()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertOneBanner(html);
        assertThat(section(html, "trademark-panel")).contains("trademark-demo-note").contains(TEXT);
        assertThat(section(html, "readiness-panel")).doesNotContain(TEXT);
    }
}
