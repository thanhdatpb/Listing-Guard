package com.thanhdatpb.listingguard.api.demo;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleHomeService;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "listing-guard.console.enabled=true", "listing-guard.console.pilot=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.auth-mode=cloudflare-access",
        "listing-guard.console.access-team-domain=https://example.cloudflareaccess.com",
        "listing-guard.console.access-aud=demo-test",
        "listing-guard.console.access-allowed-domains=example.test",
        "listing-guard.demo.enabled=true", "listing-guard.demo.client-name=demo-integration",
        "listing-guard.demo.catalog-dir=../catalogs/demo",
        "listing-guard.phrase-screening.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class DemoWorkspaceIntegrationTest {
    private static final String NAME = "demo-integration";
    private static final String EMAIL = "demo-integration@example.test";

    @Autowired DemoWorkspaceCommands demo;
    @Autowired ClientProvisioning provisioning;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConsoleHomeService home;
    @Autowired MockMvc mvc;
    @Autowired TrademarkCatalogRuntime catalog;

    @Test
    void seedsRealOutcomesAndResetsOnlyMarkedClient() throws Exception {
        var realId = provisioning.createClient("demo-integration-real").getId();
        String realEmail = "demo-integration-real@example.test";
        jdbc.update("INSERT INTO account (client_id, email, role) VALUES (?, ?, 'ADMIN')", realId, realEmail);

        demo.reset();
        long activations = jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_activation", Long.class);
        UUID demoId = demo.seed(6, 42L, EMAIL);
        assertThat(demo.seed(6, 42L, EMAIL)).isEqualTo(demoId);
        assertThat(catalog.demoStatus(demoId).orElseThrow().state()).isEqualTo(TrademarkCatalogRuntime.State.READY);
        assertThat(catalog.demoStatus(realId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_activation", Long.class)).isEqualTo(activations);
        assertThatThrownBy(() -> provisioning.createKey(NAME, "forbidden"))
                .isInstanceOf(ClientProvisioning.DemoKeyForbiddenException.class);

        assertThat(count("listing_check", demoId)).isGreaterThanOrEqualTo(150);
        assertThat(count("trademark_check", demoId)).isGreaterThanOrEqualTo(150);
        assertThat(jdbc.queryForList("SELECT DISTINCT risk_level FROM trademark_check WHERE client_id = ?", String.class, demoId))
                .contains("HIGH", "MEDIUM", "LOW");
        assertThat(count("check_history_event", demoId)).isPositive();
        assertThat(count("finding_verdict", demoId)).isPositive();
        assertThat(count("reaudit_run", demoId)).isEqualTo(2);
        assertThat(count("phrase_screening_run", demoId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT sum(rechecked) FROM seller_pilot_daily WHERE client_id = ?", Long.class, demoId))
                .isPositive();
        LocalDate monday = home.home(demoId).weekFrom();
        assertThat(home.home(demoId).empty()).isFalse();
        assertThat(home.home(realId).empty()).isTrue();
        assertThat(home.home(demoId).checked()).isEqualTo(jdbc.queryForObject("""
                SELECT count(*) FROM listing_check WHERE client_id = ? AND created_at >= ?
                """, Long.class, demoId, monday.atStartOfDay().atOffset(ZoneOffset.UTC)));

        var user = new ConsoleUser(EMAIL, demoId);
        for (String path : List.of("/console/", "/console/stats", "/console/stats/pilot", "/console/history",
                "/console/reaudit", "/console/watchlist", "/console/api-keys")) {
            mvc.perform(get(path).with(signedIn(user)).param("lang", "vi"))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("DỮ LIỆU DEMO")));
        }
        mvc.perform(get("/console/api-keys").with(signedIn(user)).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("DEMO DATA")))
                .andExpect(content().string(not(containsString("id=\"create-form\""))));
        mvc.perform(post("/console/api-keys").with(signedIn(user)).with(csrf()).param("label", "demo"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/console/").with(signedIn(new ConsoleUser(realEmail, realId))))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("id=\"demo-banner\""))));

        var dates = jdbc.queryForList("""
                SELECT check_day, checked, high, medium, low, rechecked FROM seller_pilot_daily
                WHERE client_id = ? ORDER BY check_day, marketplace_code
                """, demoId);
        demo.reset();
        assertThat(count("client", demoId)).isZero();
        assertThat(count("client", realId)).isOne();
        assertThat(count("account", realId)).isOne();
        UUID reseeded = demo.seed(6, 42L, EMAIL);
        assertThat(reseeded).isNotEqualTo(demoId);
        assertThat(jdbc.queryForList("""
                SELECT check_day, checked, high, medium, low, rechecked FROM seller_pilot_daily
                WHERE client_id = ? ORDER BY check_day, marketplace_code
                """, reseeded)).isEqualTo(dates);
        demo.reset();
        var collision = provisioning.createClient(NAME).getId();
        assertThatThrownBy(demo::reset).hasMessageContaining("not a marked demo");
        assertThatThrownBy(() -> demo.seed(6, 42L, EMAIL)).hasMessageContaining("not a marked demo");
        assertThat(count("client", collision)).isOne();
    }

    private long count(String table, UUID client) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE "
                + ("client".equals(table) ? "id" : "client_id") + " = ?", Long.class, client);
    }
}
