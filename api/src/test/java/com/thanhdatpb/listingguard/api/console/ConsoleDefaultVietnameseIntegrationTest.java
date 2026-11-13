package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A deployment for Vietnamese sellers (ADR-028): {@code listing-guard.console.default-locale=vi}. A reader with no cookie gets
 * Vietnamese, whatever their browser asks for, and can still choose English.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test",
        "listing-guard.console.default-locale=vi"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleDefaultVietnameseIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @MockitoBean
    JavaMailSender mailSender;

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    void withNoChoiceEveryPageIsVietnameseEvenForABrowserThatAsksForEnglish() throws Exception {
        ConsoleUser user = userOf(PlanTestSupport.newClient(provisioning, clients, PlanCode.FREE));

        String login = body(mvc.perform(get("/login").header(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9"))
                .andExpect(status().isOk()).andReturn());
        String dashboard = body(mvc.perform(get("/console").with(signedIn(user))).andExpect(status().isOk()).andReturn());
        String editor = body(mvc.perform(get("/console/check").with(signedIn(user))).andExpect(status().isOk()).andReturn());

        assertThat(login).contains("<html lang=\"vi\"").contains("Đăng nhập Listing Guard");
        assertThat(dashboard).contains("<html lang=\"vi\"").contains("Tổng quan").contains("Khóa API").doesNotContain("Sign out");
        assertThat(editor).contains("Kiểm tra listing").contains("Mô phỏng").doesNotContain("Simulation");
    }

    @Test
    void aReaderCanStillChooseEnglishAndTheChoiceSticks() throws Exception {
        MvcResult chosen = mvc.perform(get("/login").param("lang", "en")).andExpect(status().isOk()).andReturn();
        String next = body(mvc.perform(get("/login").cookie(new Cookie(ConsoleLocaleResolver.COOKIE, "en")))
                .andExpect(status().isOk()).andReturn());

        assertThat(body(chosen)).contains("<html lang=\"en\"").contains("Sign in to Listing Guard");
        assertThat(chosen.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith("lg_lang=en;");
        assertThat(next).contains("Sign in to Listing Guard");
    }
}
