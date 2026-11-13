package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.auth-mode=cloudflare-access",
        "listing-guard.console.access-team-domain=https://example.cloudflareaccess.com",
        "listing-guard.console.access-aud=aud-123",
        "listing-guard.console.access-allowed-domains=example.test",
        "listing-guard.console.access-auto-create=true",
        "listing-guard.console.access-default-client-name=console-default"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleCloudflareAccessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CloudflareAccessJwtVerifier verifier;

    @Test
    void verifiedAllowlistedIdentityCreatesAnAccountOnTheConfiguredDefaultClient() throws Exception {
        when(verifier.verifyEmail("signed-jwt")).thenReturn("staff@example.test");
        mvc.perform(get("/console/").header("Cf-Access-Jwt-Assertion", "signed-jwt"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT c.name FROM account a JOIN client c ON c.id=a.client_id WHERE a.email=?",
                String.class, "staff@example.test")).isEqualTo("console-default");
        assertThat(jdbc.queryForObject("SELECT plan_code FROM client WHERE name=?", String.class, "console-default"))
                .isEqualTo("FREE");
    }

    @Test
    void rejectsUnallowlistedIdentityAndDoesNotTrustBareEmailHeader() throws Exception {
        when(verifier.verifyEmail("outsider-jwt")).thenReturn("outsider@outside.test");
        mvc.perform(get("/console").header("Cf-Access-Jwt-Assertion", "outsider-jwt"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/login").param("access", "denied")).andExpect(status().isForbidden());

        mvc.perform(get("/console/").header("Cf-Access-Authenticated-User-Email", "staff@example.test"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE email IN (?,?)", Integer.class,
                "outsider@outside.test", "staff@example.test")).isZero();
    }

    @Test
    void theAccessSignInPageIsInTheLanguageOfThePage() throws Exception {
        String english = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String vietnamese = mvc.perform(get("/login").cookie(new jakarta.servlet.http.Cookie(ConsoleLocaleResolver.COOKIE, "vi")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String deniedEnglish = mvc.perform(get("/login").param("access", "denied")).andExpect(status().isForbidden()).andReturn()
                .getResponse().getContentAsString();
        String deniedVietnamese = mvc.perform(get("/login").param("access", "denied")
                        .cookie(new jakarta.servlet.http.Cookie(ConsoleLocaleResolver.COOKIE, "vi")))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();

        assertThat(english).contains("Continue with Cloudflare Access").contains("Sign in through your organization")
                .doesNotContain("??");
        assertThat(vietnamese).contains("Tiếp tục với Cloudflare Access").contains("Hãy đăng nhập qua ứng dụng Cloudflare Access")
                .doesNotContain("Continue with").doesNotContain("??");
        assertThat(deniedEnglish).contains("Your Cloudflare Access identity is not allowed to use this console.");
        assertThat(deniedVietnamese).contains("Danh tính Cloudflare Access của bạn không được phép dùng console này.");
    }
}
