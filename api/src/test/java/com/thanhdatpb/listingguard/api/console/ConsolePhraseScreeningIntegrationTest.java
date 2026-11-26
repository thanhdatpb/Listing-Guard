package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.ApiTestKeys;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.trademark.PilotCatalogTestConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.pilot=true",
        "listing-guard.console.default-locale=vi",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.auth-mode=cloudflare-access",
        "listing-guard.console.access-team-domain=https://example.cloudflareaccess.com",
        "listing-guard.console.access-aud=pilot-test",
        "listing-guard.console.access-allowed-domains=pilot.example.test",
        "listing-guard.console.access-auto-create=false",
        "listing-guard.phrase-screening.enabled=true",
        "listing-guard.trademark.ruleset=classpath:rulesets/trademark-risk-2026-12.yaml"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PilotCatalogTestConfig.class})
class ConsolePhraseScreeningIntegrationTest {
    private static final String ASSERTION = "synthetic-admin-assertion";
    private static final String HEADER = "Cf-Access-Jwt-Assertion";
    private static final String CONSOLE = "/console/phrase-screening";
    private static final String EMAIL = "phrase-admin@pilot.example.test";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @MockitoBean CloudflareAccessJwtVerifier verifier;
    @LocalServerPort int port;
    private UUID client;
    private UUID account;

    @BeforeEach
    void admin() {
        client = jdbc.queryForObject("SELECT id FROM client WHERE name=?", UUID.class, ApiTestKeys.CLIENT_1);
        jdbc.update("INSERT INTO account(id, email, client_id, role) VALUES (?, ?, ?, 'ADMIN') ON CONFLICT (email) DO NOTHING",
                UUID.randomUUID(), EMAIL, client);
        account = jdbc.queryForObject("SELECT id FROM account WHERE email=?", UUID.class, EMAIL);
        when(verifier.verifyEmail(ASSERTION)).thenReturn(EMAIL);
        jdbc.update("DELETE FROM console_phrase_mapping WHERE account_id = ?", account);
        jdbc.update("DELETE FROM phrase_screening_run WHERE client_id = ?", client);
    }

    @Test
    void screeningWritesOnlyAccountMetadataAndFeedbackSharesTheExistingStore() throws Exception {
        var session = new MockHttpSession();
        String data = "Hits,Words\n25,Northvale Baseball\n11,linen tumbler";
        mvc.perform(post(CONSOLE + "/run").header(HEADER, ASSERTION).session(session)
                .param("mode", "file").param("data", data)).andExpect(status().isForbidden());
        var response = mvc.perform(post(CONSOLE + "/run").with(csrf()).header(HEADER, ASSERTION).session(session)
                .param("mode", "file").param("data", data).param("phraseColumn", "1").param("volumeColumn", "0")
                .param("remember", "true")).andExpect(status().is3xxRedirection()).andReturn().getResponse();
        String location = response.getRedirectedUrl();
        assertThat(location).startsWith(CONSOLE + "/");
        String result = html(get(location).header(HEADER, ASSERTION).session(session).param("tab", "REVIEW"));
        assertThat(result).contains("Northvale Baseball", "Cần xem", "pilot-synthetic", "CORRECT", "FALSE_POSITIVE")
                .doesNotContain("NOT_USEFUL");
        mvc.perform(post(location + "/feedback").with(csrf()).header(HEADER, ASSERTION).header("HX-Request", "true")
                        .session(session).param("position", "0").param("verdict", "CORRECT"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT verdict FROM finding_verdict WHERE client_id = ? AND account_id = ? AND pack_id = 'phrase-screening'
                ORDER BY id DESC LIMIT 1
                """, String.class, client, account)).isEqualTo("CORRECT");
        assertThat(jdbc.queryForObject("SELECT to_jsonb(r)::text FROM phrase_screening_run r WHERE client_id = ?", String.class, client))
                .contains(account.toString(), "ETSY").doesNotContain("Northvale", "linen tumbler");
        assertThat(jdbc.queryForObject("SELECT phrase_header FROM console_phrase_mapping WHERE account_id = ?", String.class, account))
                .isEqualTo("Words");

        var file = new MockMultipartFile("file", "demo.csv", "text/csv", data.getBytes(StandardCharsets.UTF_8));
        assertThat(html(multipart(CONSOLE + "/preview").file(file).with(csrf()).header(HEADER, ASSERTION)
                .session(session).param("mode", "file"))).contains("id=\"phrase-counts\"").doesNotContain("Đã đoán cột");
        var today = LocalDate.now(clock);
        String period = "?from=" + today.minusDays(1) + "&to=" + today + "&marketplace=ETSY";
        assertThat(html(get("/console/stats" + period).header(HEADER, ASSERTION))).contains("id=\"phrase-weekly\"");
        assertThat(html(get("/console/stats/pilot" + period).header(HEADER, ASSERTION))).contains("id=\"phrase-weekly\"");
        assertThat(html(get("/console/stats/pilot.csv" + period).header(HEADER, ASSERTION)))
                .contains("Thống kê kiểm cụm từ theo tuần", ",1,2,0.0000,0.5000,0.5000");
        assertThat(html(get("/console/readiness").header(HEADER, ASSERTION))).contains("Kiểm keyword đã bật");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LG_RUN_PHRASE_BROWSER", matches = "1")
    void chromiumRunsTheRealConsoleWithSyntheticData() throws Exception {
        Path log = Files.createTempFile("phrase-console-browser", ".log");
        try {
            var browser = new ProcessBuilder("python3", "../scripts/test-console-phrase-screening.py")
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            browser.environment().put("LG_PILOT_URL", "http://localhost:" + port);
            browser.environment().put("LG_PILOT_TEST_ASSERTION", ASSERTION);
            Process process = browser.start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Phrase console browser test timed out");
            }
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private String html(RequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
