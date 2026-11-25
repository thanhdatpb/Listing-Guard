package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.ApiTestKeys;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.trademark.PilotCatalogTestConfig;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
        "listing-guard.console.policy-changes-enabled=false",
        "listing-guard.console.pod-enabled=false",
        "listing-guard.review-cases.enabled=false",
        "listing-guard.verified-cases.enabled=false",
        "listing-guard.analyzer.enabled=false",
        "listing-guard.image.enabled=false",
        "listing-guard.fixes.enabled=true",
        "listing-guard.trademark.ruleset=classpath:rulesets/trademark-risk-2026-12.yaml",
        "listing-guard.plans.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PilotCatalogTestConfig.class})
class ConsoleSellerPilotIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PilotMetrics metrics;
    @Autowired Clock clock;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    @MockitoBean CloudflareAccessJwtVerifier verifier;

    private static final String MEMBER = "seller-assertion";
    private static final String ADMIN = "lead-assertion";
    private static final String HEADER = "Cf-Access-Jwt-Assertion";

    @BeforeEach
    void accounts() {
        account(ApiTestKeys.CLIENT_1, "seller", MEMBER, "MEMBER");
        account(ApiTestKeys.CLIENT_1, "lead", ADMIN, "ADMIN");
        account(ApiTestKeys.CLIENT_2, "other", "other-assertion", "ADMIN");
    }

    @Test
    void memberAndLeadSeeOnlyTheirPagesAndApiCapabilitiesMatch() throws Exception {
        String seller = html(get("/console/").header(HEADER, MEMBER));
        String lead = html(get("/console/").header(HEADER, ADMIN).param("lang", "en"));
        assertThat(seller).contains("lang=\"vi\"", "Tính năng đang bật trong pilot", "Theo dõi", "Dán từ Sheet",
                        "href=\"/console/check\"", "href=\"/console/bulk\"", "href=\"/console/check/edit\"",
                        "href=\"/console/history\"", "href=\"/console/reaudit\"")
                .doesNotContain("href=\"/console/watchlist\"", "href=\"/console/stats\"", "href=\"/console/api-keys\"",
                        "href=\"/console/review-queue\"", "href=\"/console/pod-readiness\"", "href=\"/console/policy-changes\"", "Thử nghiệm");
        assertThat(lead).contains("lang=\"en\"", "Available in this pilot", "href=\"/console/stats\"",
                "href=\"/console/watchlist\"", "href=\"/console/api-keys\"").doesNotContain("Experimental");
        for (String page : List.of("/console/check", "/console/bulk", "/console/check/edit", "/console/history", "/console/reaudit")) {
            mvc.perform(get(page).header(HEADER, MEMBER)).andExpect(status().isOk());
        }
        for (String page : List.of("/console/watchlist", "/console/stats", "/console/stats/pilot", "/console/stats/pilot.csv",
                "/console/api-keys")) {
            mvc.perform(get(page).header(HEADER, MEMBER)).andExpect(status().isForbidden());
            mvc.perform(get(page).header(HEADER, ADMIN)).andExpect(status().isOk());
        }
        mvc.perform(post("/console/api-keys").header(HEADER, MEMBER).with(csrf())).andExpect(status().isForbidden());
        for (String page : List.of("/console/review-queue", "/console/pod-readiness", "/console/policy-changes")) {
            mvc.perform(get(page).header(HEADER, ADMIN)).andExpect(status().isNotFound());
        }
        mvc.perform(post("/console/check/edit/policy-checks").header(HEADER, MEMBER).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"Synthetic\",\"description\":\"Synthetic\",\"tags\":[]}}"))
                .andExpect(status().isNotFound());
        var capability = mapper.readTree(html(get("/api/v1/capabilities").header(ApiTestKeys.HEADER, ApiTestKeys.KEY_1)));
        for (var pack : capability.path("packs")) {
            assertThat(seller.contains("data-pack=\"" + pack.path("id").asText() + "\"")).isEqualTo(pack.path("available").asBoolean());
        }
        assertThat(capability.at("/plans/enforced").asBoolean()).isFalse();
        assertThat(capability.at("/fixes/enabled").asBoolean()).isTrue();
        assertThat(capability.at("/trademarkCatalog/state").asText()).isEqualTo("READY");
    }

    @Test
    void reportAggregatesRanksAndRechecksByDateAndMarketplaceForTheCurrentClient() throws Exception {
        UUID one = client(ApiTestKeys.CLIENT_1);
        UUID two = client(ApiTestKeys.CLIENT_2);
        jdbc.update("DELETE FROM seller_pilot_daily");
        metrics.record(one, "ETSY", List.of(new PilotMetrics.Observation("HIGH", false),
                new PilotMetrics.Observation("LOW", true), new PilotMetrics.Observation(null, false)));
        metrics.record(one, "ETSY", List.of(new PilotMetrics.Observation("MEDIUM", false)));
        metrics.record(one, "AMAZON", List.of(new PilotMetrics.Observation("LOW", false)));
        metrics.record(two, "ETSY", List.of(new PilotMetrics.Observation("HIGH", true)));
        jdbc.update("DELETE FROM finding_verdict WHERE rule_id IN ('pilot-synthetic-rule', 'other-tenant-rule')");
        verdict(one, "seller", "pilot-synthetic-rule", "FALSE_POSITIVE");
        verdict(one, "seller", "pilot-synthetic-rule", "CORRECT");
        verdict(two, "other", "other-tenant-rule", "FALSE_POSITIVE");
        var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        String period = "?from=" + today.minusDays(1) + "&to=" + today + "&marketplace=ETSY";
        String csv = html(get("/console/stats/pilot.csv" + period).header(HEADER, ADMIN));
        assertThat(csv).contains("Báo cáo pilot", today + ",4,1,0.3333,1,0.3333,1,0.3333,1,1",
                today.minusDays(1) + ",0,0,,0,,0,,0,0", "listing-rules,pilot-synthetic-rule,RULE,2,1,0.5000")
                .doesNotContain("@pilot.example.test", "private synthetic note", "other-tenant-rule");
        String vietnamese = html(get("/console/stats/pilot" + period).header(HEADER, ADMIN));
        assertThat(vietnamese).contains("Báo cáo pilot", "Đã sửa và kiểm lại", "Báo nhầm", "id=\"pilot-daily\"");
        assertThat(html(get("/console/stats/pilot" + period + "&lang=en").header(HEADER, ADMIN)))
                .contains("Pilot report", "Edited and rechecked", "Daily listing checks");
        assertThat(html(get("/console/stats/pilot.csv" + period).header(HEADER, "other-assertion")))
                .contains(today + ",1,1,1.0000,0,0.0000,0,0.0000,0,1");
        mvc.perform(get("/console/stats/pilot.csv?from=bad").header(HEADER, ADMIN)).andExpect(status().isBadRequest());
    }

    @Test
    void reportCountsContentChecksApartAndShowsThemOnlyWhenThePeriodHasSome() throws Exception {
        UUID one = client(ApiTestKeys.CLIENT_1);
        jdbc.update("DELETE FROM seller_pilot_daily");
        metrics.record(one, "ETSY", List.of(new PilotMetrics.Observation("HIGH", false)));
        var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        String period = "?from=" + today + "&to=" + today + "&marketplace=ETSY";
        String onlyFull = html(get("/console/stats/pilot.csv" + period).header(HEADER, ADMIN));
        assertThat(onlyFull).contains(today + ",1,1,1.0000,0,0.0000,0,0.0000,0,0")
                .doesNotContain("Lượt kiểm listing theo ngày (kiểm nội dung)");
        assertThat(html(get("/console/stats/pilot" + period).header(HEADER, ADMIN)))
                .doesNotContain("id=\"pilot-daily-content\"").contains("Lượt kiểm listing theo ngày");

        metrics.record(one, "ETSY", com.thanhdatpb.listingguard.api.check.CheckScope.Kind.CONTENT,
                List.of(new PilotMetrics.Observation("LOW", false), new PilotMetrics.Observation(null, false)));

        String csv = html(get("/console/stats/pilot.csv" + period).header(HEADER, ADMIN));
        assertThat(csv).as("the full count is not changed by content checks").contains(today + ",1,1,1.0000,0,0.0000,0,0.0000,0,0")
                .contains("Lượt kiểm listing theo ngày (kiểm nội dung)").contains(today + ",2,0,0.0000,0,0.0000,1,1.0000,1,0");
        String vietnamese = html(get("/console/stats/pilot" + period).header(HEADER, ADMIN));
        assertThat(vietnamese).contains("id=\"pilot-daily\"", "id=\"pilot-daily-content\"", "Lượt kiểm listing theo ngày (kiểm đầy đủ)",
                "Lượt kiểm listing theo ngày (kiểm nội dung)", "không cộng vào lượt kiểm đầy đủ");
        assertThat(html(get("/console/stats/pilot" + period + "&lang=en").header(HEADER, ADMIN)))
                .contains("Daily listing checks (full check)", "Daily listing checks (content check)", "never added to the full checks");
        assertThat(jdbc.queryForList("SELECT scope FROM seller_pilot_daily WHERE client_id = ? ORDER BY scope", String.class, one))
                .containsExactly("CONTENT", "FULL");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LG_RUN_PILOT_BROWSER", matches = "1")
    void browserMeasuresRealConsoleWithSyntheticListings() throws Exception {
        UUID client = client(ApiTestKeys.CLIENT_1);
        jdbc.update("DELETE FROM seller_pilot_daily WHERE client_id=?", client);
        ProcessBuilder browser = new ProcessBuilder("python3", "../scripts/test-console-seller-pilot.py").redirectErrorStream(true);
        browser.environment().put("LG_PILOT_URL", "http://localhost:" + port);
        browser.environment().put("LG_PILOT_TEST_ASSERTION", MEMBER);
        String screenshot = System.getenv("LG_PILOT_SCREENSHOT");
        if (screenshot != null) {
            browser.environment().put("LG_PILOT_SCREENSHOT", Path.of(screenshot).toAbsolutePath().toString());
        }
        Process process = browser.start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Playwright did not finish");
        }
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(result).isZero();
        assertThat(result).contains("6 clicks");
        System.out.print(result);
        assertThat(jdbc.queryForObject("SELECT checked FROM seller_pilot_daily WHERE client_id=?", Long.class, client)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT low FROM seller_pilot_daily WHERE client_id=?", Long.class, client)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT rechecked FROM seller_pilot_daily WHERE client_id=?", Long.class, client)).isEqualTo(1L);
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void verdict(UUID client, String account, String rule, String verdict) {
        jdbc.update("""
                INSERT INTO finding_verdict (client_id, account_id, pack_id, check_id, finding_position,
                    marketplace_code, rule_id, source, finding_level, analysis_version, verdict, note)
                SELECT ?, id, 'listing-rules', ?, 0, 'ETSY', ?, 'RULE', 'WARNING', 'pilot-test', ?, 'private synthetic note'
                FROM account WHERE email = ?
                """, client, UUID.randomUUID(), rule, verdict, account + "@pilot.example.test");
    }

    private UUID client(String name) {
        return jdbc.queryForObject("SELECT id FROM client WHERE name=?", UUID.class, name);
    }

    private void account(String client, String name, String assertion, String role) {
        String email = name + "@pilot.example.test";
        jdbc.update("INSERT INTO account(id, email, client_id, role) VALUES (?, ?, ?, ?) ON CONFLICT (email) DO NOTHING",
                UUID.randomUUID(), email, client(client), role);
        when(verifier.verifyEmail(assertion)).thenReturn(email);
    }
}
