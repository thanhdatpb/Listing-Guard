package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.FixesProperties;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.bulk.BulkProperties;
import com.thanhdatpb.listingguard.api.trademark.PilotCatalogTestConfig;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The "Sẵn sàng" page of the pilot (ADR-053) in a deployment that is set up like the pilot template: a jsonl-2 catalog with tiers, the
 * ruleset {@code trademark-risk-2026-12}, the content scope by default and every AI flag off. A client of its own for each test, so the
 * counts are the ones the test made: no watchlist, then one entry; an administrator and a seller; an account the sign-in settings would
 * refuse. Only an ADMIN sees the page, and it speaks both languages.
 */
@SpringBootTest(properties = {
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
        "listing-guard.plans.enabled=false",
        // the tests give every context the fake embedding provider; this is the switch that keeps the configured default (none)
        "test.default-embedding-provider=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PilotCatalogTestConfig.class})
class ConsolePilotReadinessIntegrationTest {

    private static final String HEADER = "Cf-Access-Jwt-Assertion";
    /** The lines of the page: four of the trademark, two of the client, seven settings. */
    private static final int LINES = 13;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClientProvisioning provisioning;
    @Autowired ConsoleAccountRepository accounts;
    @Autowired WatchlistService watchlist;
    @Autowired TrademarkCatalogRuntime catalog;
    @Autowired TrademarkProperties trademark;
    @Autowired TrademarkRulesets rulesets;
    @Autowired ConsoleProperties console;
    @Autowired BulkProperties bulk;
    @Autowired FixesProperties fixes;
    @Autowired ConsolePilot pilot;
    @Autowired Environment environment;
    @Autowired ConsoleMessages messages;
    @MockitoBean CloudflareAccessJwtVerifier verifier;

    private UUID client;
    private String lead;
    private String seller;
    private String id;

    @BeforeEach
    void aClientWithALeadAndASeller() {
        id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Client created = provisioning.createClient("ready" + id);
        client = created.getId();
        lead = account("lead-" + id + "@pilot.example.test", ConsoleRole.ADMIN, "lead-" + id);
        seller = account("seller-" + id + "@pilot.example.test", ConsoleRole.MEMBER, "seller-" + id);
    }

    /** Makes an account of this test's client and the assertion that signs in as it. */
    private String account(String email, ConsoleRole role, String assertion) {
        accounts.saveAndFlush(new ConsoleAccount(email, client, role));
        when(verifier.verifyEmail(assertion)).thenReturn(email);
        return assertion;
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String page(String assertion, String language) throws Exception {
        return html(get("/console/readiness").header(HEADER, assertion).cookie(new Cookie(ConsoleLocaleResolver.COOKIE, language)));
    }

    /** "ok" or "missing": the state of the line with this id. */
    private static String state(String page, String key) {
        Matcher row = Pattern.compile("<tr id=\"readiness-" + key + "\" data-state=\"(ok|missing)\"").matcher(page);
        assertThat(row.find()).as("the line " + key).isTrue();
        return row.group(1);
    }

    private PilotReadiness fresh() {
        return new PilotReadiness(catalog, trademark, rulesets, jdbc, accounts, console, bulk, fixes, pilot, environment, messages);
    }

    private static PilotReadiness.Item item(PilotReadiness.View view, String key) {
        return view.sections().stream().flatMap(section -> section.items().stream()).filter(item -> item.key().equals(key))
                .findFirst().orElseThrow();
    }

    // ---- who sees it

    @Test
    void onlyAnAdminSeesThePageAndItsLinkAndItIsNeverCached() throws Exception {
        mvc.perform(get("/console/readiness").header(HEADER, seller)).andExpect(status().isForbidden());
        var admin = mvc.perform(get("/console/readiness").header(HEADER, lead)).andExpect(status().isOk()).andReturn().getResponse();

        assertThat(admin.getHeader("Cache-Control")).contains("no-store");
        assertThat(html(get("/console/").header(HEADER, lead))).contains("href=\"/console/readiness\"");
        assertThat(html(get("/console/").header(HEADER, seller))).doesNotContain("href=\"/console/readiness\"");
        assertThat(html(get("/console/bulk").header(HEADER, seller))).doesNotContain("href=\"/console/readiness\"");
    }

    // ---- what it says

    @Test
    void aDeploymentSetUpLikeThePilotTemplateIsInOrderExceptForTheClientsOwnWatchlist() throws Exception {
        String vietnamese = page(lead, "vi");

        assertThat(state(vietnamese, "catalog")).isEqualTo("ok");
        assertThat(state(vietnamese, "tier")).isEqualTo("ok");
        assertThat(state(vietnamese, "wordlist")).isEqualTo("ok");
        assertThat(state(vietnamese, "ruleset")).isEqualTo("ok");
        assertThat(state(vietnamese, "scope")).isEqualTo("ok");
        assertThat(state(vietnamese, "fixes")).isEqualTo("ok");
        for (String flag : List.of("ai-policy", "ai-llm", "ai-judge", "ai-rewrite", "ai-embedding")) {
            assertThat(state(vietnamese, flag)).as(flag).isEqualTo("ok");
        }
        assertThat(state(vietnamese, "users")).isEqualTo("ok");
        assertThat(state(vietnamese, "watchlist")).as("an empty watchlist").isEqualTo("missing");
        assertThat(vietnamese).contains("Đang phục vụ phiên bản " + catalog.status().detail())
                .contains("Catalog có tier (jsonl-2).").contains("Catalog được chuyển đổi cùng wordlist.")
                .contains("trademark-risk-2026-12, có hiệu chỉnh theo tier.").contains("0 mục đang bật.")
                .contains("1 ADMIN, 1 MEMBER.").contains("Kiểm nội dung (chỉ các cột có trong bảng).")
                .contains("Còn 1 mục thiếu (12/13 mục đạt).");
    }

    @Test
    void aWatchlistWithAnEnabledEntryMakesTheClientReadyAndADisabledOneDoesNot() throws Exception {
        watchlist.replace(client, List.of(new EntryInput("Brimwick Otters " + id, List.of("Otterz"), List.of(25), null, "đã bị claim",
                false)), "test", null);
        assertThat(state(page(lead, "vi"), "watchlist")).as("entries that are off do not count").isEqualTo("missing");

        watchlist.replace(client, List.of(new EntryInput("Brimwick Otters " + id, List.of("Otterz"), List.of(25), null, "đã bị claim",
                true), new EntryInput("Sunny Foxes " + id, List.of(), List.of(), null, null, true)), "test", null);
        String ready = page(lead, "vi");

        assertThat(state(ready, "watchlist")).isEqualTo("ok");
        assertThat(ready).contains("2 mục đang bật.").contains("13/13 mục đạt. Bạn có thể gửi hướng dẫn cho seller.");
        assertThat(ready).doesNotContain("Brimwick").doesNotContain("Sunny Foxes");
    }

    @Test
    void anAccountTheSignInSettingsWouldRefuseMakesTheUsersLineMissing() throws Exception {
        account("outsider-" + id + "@elsewhere.example.test", ConsoleRole.MEMBER, "outsider-" + id);

        String page = page(lead, "vi");

        assertThat(state(page, "users")).isEqualTo("missing");
        assertThat(page).contains("1 ADMIN, 2 MEMBER.").contains("1 tài khoản chưa được phép đăng nhập");
        assertThat(page).doesNotContain("outsider-" + id);
    }

    @Test
    void aClientWithNoSellerIsNotReady() throws Exception {
        jdbc.update("DELETE FROM account WHERE email = ?", "seller-" + id + "@pilot.example.test");

        String page = page(lead, "vi");

        assertThat(state(page, "users")).isEqualTo("missing");
        assertThat(page).contains("1 ADMIN, 0 MEMBER.");
    }

    @Test
    void aCatalogWhoseEntriesSayTheDictionaryWasUnavailableHasNoWordListAndOneWithoutSignalsHasNoAnswer() {
        // the catalog tables are append-only, so the sample of the catalog is stubbed: the real one is what the other tests read
        assertThat(item(fresh().view(client), "wordlist").ok()).as("the pilot catalog was converted with a word list").isTrue();

        PilotReadiness unavailable = org.mockito.Mockito.spy(fresh());
        org.mockito.Mockito.doReturn(List.of("DICTIONARY_UNAVAILABLE")).when(unavailable).sampleReasons(org.mockito.ArgumentMatchers.anyLong());
        PilotReadiness.View without = unavailable.view(client);
        assertThat(item(without, "wordlist").ok()).isFalse();
        assertThat(item(without, "tier").ok()).as("tiers are still there").isTrue();
        assertThat(item(without, "catalog").ok()).isTrue();

        PilotReadiness silent = org.mockito.Mockito.spy(fresh());
        org.mockito.Mockito.doReturn(List.of()).when(silent).sampleReasons(org.mockito.ArgumentMatchers.anyLong());
        assertThat(item(silent.view(client), "wordlist").ok()).as("no answer is not yes").isFalse();
    }

    // ---- both languages

    @Test
    void everyLineIsInTheLanguageOfThePageWithItsStateAndItsGuidance() throws Exception {
        String english = page(lead, "en");
        String vietnamese = page(lead, "vi");

        assertThat(english).contains("<html lang=\"en\"").contains("Pilot readiness").contains("Served catalog")
                .contains("Distinctiveness tiers").contains("Word list").contains("Enabled watchlist entries")
                .contains("Default check scope").contains("AI: trademark judge").contains("Missing").contains("OK")
                .contains("Serving version " + catalog.status().detail()).contains("0 entries are on.")
                .contains("Fill in docs/samples/watchlist-template.csv").contains("1 of 13 items are missing")
                .contains("Content check (only the columns in the table).")
                .doesNotContain("Đạt").doesNotContain("Thiếu").doesNotContain("Sẵn sàng cho pilot");
        assertThat(vietnamese).contains("<html lang=\"vi\"").contains("Sẵn sàng cho pilot").contains("Catalog đang phục vụ")
                .contains("Tín hiệu tier").contains("Wordlist từ điển").contains("Mục watchlist đang bật")
                .contains("Phạm vi kiểm mặc định").contains("AI: judge nhãn hiệu").contains("Thiếu").contains("Đạt")
                .contains("Điền docs/samples/watchlist-template.csv").doesNotContain("Pilot readiness").doesNotContain("Missing");
        for (String page : List.of(english, vietnamese)) {
            assertThat(page).doesNotContain("??").doesNotContain("#{").doesNotContain("${");
            assertThat(Pattern.compile("data-state=\"(ok|missing)\"").matcher(page).results().count()).as("one line each")
                    .isEqualTo(LINES);
        }
    }

    @Test
    void theStateOfEveryLineIsAWordAndNotOnlyAColor() throws Exception {
        String page = page(lead, "vi");

        assertThat(Pattern.compile("lg-pill--clean\">Đạt</span>").matcher(page).results().count()).isEqualTo(LINES - 1);
        assertThat(Pattern.compile("lg-pill--error\">Thiếu</span>").matcher(page).results().count()).isEqualTo(1);
    }
}
