package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.feedback.FeedbackVerdict;
import com.thanhdatpb.listingguard.api.feedback.FindingFeedbackService;
import com.thanhdatpb.listingguard.api.feedback.Reviewer;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningRequest;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckRequest;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "listing-guard.console.enabled=true", "listing-guard.console.pilot=true",
        "listing-guard.console.role-home-enabled=true", "listing-guard.console.base-url=http://localhost",
        "listing-guard.phrase-screening.enabled=true"
})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RoleHomeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CheckService checks;
    @Autowired ClientProvisioning clients;
    @Autowired ConfigurableEnvironment environment;
    @Autowired Clock clock;
    @Autowired RoleHomeService reports;
    @Autowired WatchlistService watchlists;
    @Autowired TrademarkCheckService trademarks;
    @Autowired PhraseScreeningService phrases;
    @Autowired FindingFeedbackService feedback;

    @Test
    void periodsRolesPrintAndLegacyExportUseTenantData() throws Exception {
        UUID client = clients.createClient("home-test").getId();
        UUID other = clients.createClient("home-test-other").getId();
        ConsoleUser admin = user(client, "manager", "ADMIN");
        ConsoleUser seller = user(client, "seller", "MEMBER");
        ConsoleUser colleague = user(client, "colleague", "MEMBER");
        ConsoleUser outsider = user(other, "outsider", "ADMIN");
        String empty = home(admin, "vi", "7");
        assertThat(empty).contains("Tổng quan", "id=\"manager-empty\"", "Chưa ghi nhận đạt cổng");
        assertThat(home(seller, "en", "7")).contains("My work", "No recorded checks yet", "id=\"work-guide\"");

        UUID sellerCheck = check(seller, "OWN-SKU", 1);
        check(colleague, "COLLEAGUE-SKU", 11);
        check(admin, "OLD-SKU", 45);
        check(outsider, "OTHER-TENANT-SKU", 1);

        for (int i = 0; i < 3; i++) {
            int days = List.of(7, 30, 90).get(i);
            var report = reports.overview(client, reports.period(days));
            assertThat(report.checks()).isEqualTo(i + 1);
            assertThat(report.errors()).isEqualTo(i + 1);
            assertThat(report.activeAccounts()).isEqualTo(i + 1);
            assertThat(report.chart().columns()).hasSize(12);
            assertThat(home(admin, "vi", String.valueOf(days))).contains(
                    "id=\"manager-checks\">" + (i + 1), "id=\"manager-errors\">" + (i + 1), "manager-weekly-table")
                    .doesNotContain("OWN-SKU", "COLLEAGUE-SKU", "OTHER-TENANT-SKU", "private fixture");
        }
        watchlists.replace(client, List.of(new EntryInput("Role Home Falcons", List.of(), List.of(25), null, null, true)),
                "console:" + account(admin), null);
        var title = JsonNodeFactory.instance.objectNode().put("title", "Role Home Falcons gift");
        trademarks.check(client, new TrademarkCheckRequest("ETSY", title, null));
        phrases.screenConsole(client, account(seller), new PhraseScreeningRequest(
                List.of(new PhraseScreeningRequest.PhraseInput("Role Home Falcons", null)), "ETSY", null));
        feedback.record(client, Reviewer.account(account(seller)), new FeedbackTarget("listing-rules", sellerCheck, 0),
                FeedbackVerdict.FALSE_POSITIVE, null);
        var updated = reports.overview(client, reports.period(7));
        assertThat(updated.watchlistHolds()).isEqualTo(1);
        assertThat(updated.keywords()).isEqualTo(1);
        assertThat(updated.keywordBlocks()).isEqualTo(1);
        assertThat(updated.falsePositiveRate()).isEqualTo(1.0);
        assertThat(updated.marks()).containsExactly(new RoleHomeService.Mark("Role Home Falcons", true, 1));
        assertThat(reports.overview(other, reports.period(7)).marks()).isEmpty();
        assertThat(home(outsider, "en", "90")).contains("id=\"manager-checks\">1");
        assertThat(home(seller, "vi", "30")).contains("Việc của tôi", "OWN-SKU", "Sửa listing", "work-guide")
                .doesNotContain("COLLEAGUE-SKU", "OTHER-TENANT-SKU", "manager-report-link");
        mvc.perform(post("/console/home/hide-guide").with(signedIn(seller))).andExpect(status().isForbidden());
        mvc.perform(post("/console/home/hide-guide").with(signedIn(seller)).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(home(seller, "vi", "7")).doesNotContain("id=\"work-guide\"");
        assertThat(home(colleague, "vi", "7")).contains("id=\"work-guide\"");

        mvc.perform(post("/console/bulk/run").with(signedIn(seller)).with(csrf())
                        .param("marketplace", "ETSY").param("header", "yes").param("delimiter", "tab").param("scope", "content")
                        .param("data", "SKU\tTitle\tDescription\tTags\nOWN-SKU\tBlue ceramic mug\tMade to order\tmug")
                        .param("col.0", "sku").param("col.1", "title").param("col.2", "description").param("col.3", "tags"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM check_history_event h JOIN listing_check c ON c.id = h.check_id AND c.client_id = h.client_id
                WHERE h.client_id = ? AND h.actor_id = ? AND h.sku = 'OWN-SKU' AND c.scope = 'CONTENT'
                  AND NOT EXISTS (SELECT 1 FROM finding f WHERE f.check_id = c.id AND f.severity = 'ERROR')
                """, Long.class, client, account(seller).toString())).isOne();
        assertThat(reports.work(client, account(seller)).needsFix()).anyMatch(
                item -> item.checkId().equals(sellerCheck) && item.scope().equals("FULL"));

        mvc.perform(get("/console/stats/pilot").with(signedIn(seller))).andExpect(status().isForbidden());
        byte[] csv = csv(admin);
        String managerPrint = mvc.perform(get("/console/stats/pilot").with(signedIn(admin)).param("lang", "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(managerPrint).contains("Manager report", "lg-manager-print", "Print / save PDF", "manager-print.js",
                "manager-weekly-table", "Catalog tiers and thresholds are unverified").doesNotContain("private fixture");
        var properties = new MapPropertySource("legacy-home", Map.of("listing-guard.console.role-home-enabled", false));
        environment.getPropertySources().addFirst(properties);
        try {
            assertThat(csv(admin)).containsExactly(csv);
            assertThat(home(admin, "en", "30")).contains("id=\"home\"").doesNotContain("manager-weekly-table");
            mvc.perform(post("/console/home/hide-guide").with(signedIn(seller)).with(csrf())).andExpect(status().isNotFound());
        } finally {
            environment.getPropertySources().remove(properties.getName());
        }
    }

    private ConsoleUser user(UUID client, String name, String role) {
        String email = name + "@role-home.example.test";
        jdbc.update("INSERT INTO account (id, email, client_id, role) VALUES (?, ?, ?, ?)", UUID.randomUUID(), email, client, role);
        return new ConsoleUser(email, client);
    }

    private UUID check(ConsoleUser user, String sku, int days) {
        var listing = JsonNodeFactory.instance.objectNode().put("description", "private fixture " + sku);
        UUID id = checks.checkDetailed(user.clientId(), new CheckRequest("ETSY", listing)).checkId();
        var time = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(days).atStartOfDay().atOffset(ZoneOffset.UTC);
        jdbc.update("UPDATE listing_check SET created_at = ? WHERE client_id = ? AND id = ?", time, user.clientId(), id);
        jdbc.update("""
                INSERT INTO check_history_event (id, client_id, pack_id, check_id, marketplace_code, actor_kind, actor_id, checked_at, sku)
                VALUES (?, ?, 'listing-rules', ?, 'ETSY', 'ACCOUNT', ?, ?, ?)
                """, UUID.randomUUID(), user.clientId(), id, account(user).toString(), time, sku);
        return id;
    }

    private UUID account(ConsoleUser user) {
        return jdbc.queryForObject("SELECT id FROM account WHERE email = ?", UUID.class, user.email());
    }

    private String home(ConsoleUser user, String lang, String period) throws Exception {
        return mvc.perform(get("/console/").with(signedIn(user)).param("lang", lang).param("days", period))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private byte[] csv(ConsoleUser user) throws Exception {
        return mvc.perform(get("/console/stats/pilot.csv").with(signedIn(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }
}
