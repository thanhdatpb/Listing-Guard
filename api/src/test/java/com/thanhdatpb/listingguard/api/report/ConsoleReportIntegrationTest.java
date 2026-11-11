package com.thanhdatpb.listingguard.api.report;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.section;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The "Report" page of the console (ADR-018): the signed-in account's own numbers from the same service as the endpoint, a form
 * that is a plain GET, a refusal as a 400 page, every value escaped and no script on the page, and sign-in required. (With the
 * console off there is no route: {@code HealthReportApiIntegrationTest}, whose context has it off.)
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class ConsoleReportIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-11-03T10:00:00Z");
    private static final Instant UNTIL = CREATED.plus(Duration.ofDays(30));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    MutableClock clock;

    @MockitoBean
    JavaMailSender mailSender;

    private HealthReportTestData data;

    @BeforeEach
    void atTheStartOfTheReportWeek() {
        clock.set(NOW);
        data = new HealthReportTestData(jdbc);
    }

    private TestClient client() {
        return newClient(provisioning, clients, PlanCode.PRO);
    }

    private String page(ConsoleUser user, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/console/report").with(signedIn(user));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String refused(ConsoleUser user, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/console/report").with(signedIn(user));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void theSignedInAccountSeesItsOwnClientsNumbersOnly() throws Exception {
        TestClient mine = client();
        TestClient other = client();
        for (int i = 0; i < 3; i++) {
            UUID listing = data.listing(mine.id(), "ETSY", CREATED);
            data.finding(listing, "ERROR", "rule_only_mine");
        }
        UUID tm = data.trademark(mine.id(), "ETSY", CREATED, UNTIL, "COMPLETE", "HIGH");
        data.trademarkFinding(tm, 0, "catalog_exact", "HIGH", "MARK_ONLY_MINE");
        UUID theirs = data.listing(other.id(), "ETSY", CREATED);
        data.finding(theirs, "ERROR", "rule_only_theirs");
        UUID theirTm = data.trademark(other.id(), "ETSY", CREATED, UNTIL, "COMPLETE", "HIGH");
        data.trademarkFinding(theirTm, 0, "catalog_exact", "HIGH", "MARK_ONLY_THEIRS");

        String html = page(userOf(mine), "from", "2026-11-02", "to", "2026-11-08");

        assertThat(section(html, "overview")).contains("listing-rules</td>").containsPattern("listing-rules</td>\\s*<td class=\"n\">3</td>")
                .containsPattern("trademark-risk</td>\\s*<td class=\"n\">1</td>");
        assertThat(section(html, "listing-rules")).contains("rule_only_mine");
        assertThat(section(html, "trademark-risk")).contains("MARK_ONLY_MINE");
        assertThat(html).doesNotContain("rule_only_theirs").doesNotContain("MARK_ONLY_THEIRS");
        // and the other account sees the other numbers
        String theirPage = page(userOf(other), "from", "2026-11-02", "to", "2026-11-08");
        assertThat(theirPage).contains("rule_only_theirs").contains("MARK_ONLY_THEIRS").doesNotContain("rule_only_mine");
        assertThat(section(theirPage, "overview")).containsPattern("listing-rules</td>\\s*<td class=\"n\">1</td>");
    }

    @Test
    void withoutADatesItShowsTheLastSevenCompleteDaysAndTheFormKeepsThem() throws Exception {
        TestClient c = client();

        String html = page(userOf(c));

        assertThat(section(html, "overview")).contains("2026-11-02").contains("2026-11-08").contains("<span>7</span> days")
                .contains("2026-11-09T03:00:00Z");
        assertThat(html).contains("name=\"from\"").contains("value=\"2026-11-02\"").contains("value=\"2026-11-08\"");
        assertThat(section(html, "by-week")).contains("2026-W45");
        // the report form is a plain GET; the only POST on the page is the sign-out button of the bar
        assertThat(html).containsPattern("<form[^>]*id=\"report-form\"[^>]*method=\"get\"[^>]*>");
        assertThat(html).doesNotContainPattern("<form[^>]*id=\"report-form\"[^>]*method=\"post\"");
    }

    @Test
    void theMarketplaceIsKeptInTheFormAndFiltersTheNumbers() throws Exception {
        TestClient c = client();
        data.listing(c.id(), "ETSY", CREATED);
        data.listing(c.id(), "AMAZON", CREATED);
        data.listing(c.id(), "AMAZON", CREATED);

        String html = page(userOf(c), "from", "2026-11-02", "to", "2026-11-08", "marketplace", "amazon");

        assertThat(section(html, "overview")).containsPattern("listing-rules</td>\\s*<td class=\"n\">2</td>").contains("AMAZON");
        assertThat(html).containsPattern("<option value=\"AMAZON\"[^>]*selected");
    }

    @Test
    void aPeriodOlderThanTheRetentionIsFlagged() throws Exception {
        String html = page(userOf(client()), "from", "2026-10-01", "to", "2026-10-31");

        assertThat(section(html, "coverage")).contains("complete from 2026-10-11").contains("notice refused");
    }

    @Test
    void aPeriodOrMarketplaceThatCannotBeUsedIsA400PageWithTheMessage() throws Exception {
        ConsoleUser user = userOf(client());

        assertThat(refused(user, "from", "2026-11-08", "to", "2026-11-02")).contains("from must not be after to.")
                .contains("id=\"report-form\"").doesNotContain("id=\"overview\"");
        assertThat(refused(user, "from", "2026-01-01", "to", "2026-04-03")).contains("more than the 92 a report may cover");
        assertThat(refused(user, "from", "2026-11-02")).contains("to is required");
        assertThat(refused(user, "from", "2026-11-02", "to", "2026-11-08", "marketplace", "NOPE")).contains("Unknown marketplace");
        assertThat(refused(user, "from", "<script>alert(1)</script>", "to", "2026-11-08")).doesNotContain("<script>alert(1)</script>")
                .contains("from is not a date written as YYYY-MM-DD.");
    }

    @Test
    void everyValueIsEscapedAndThePageHasNoScript() throws Exception {
        TestClient c = client();
        UUID listing = data.listing(c.id(), "ETSY", CREATED);
        data.finding(listing, "ERROR", "<script>alert(1)</script>");
        UUID tm = data.trademark(c.id(), "ETSY", CREATED, UNTIL, "COMPLETE", "HIGH");
        data.trademarkFinding(tm, 0, "catalog_exact", "HIGH", "<img src=x onerror=alert(1)>");

        String html = page(userOf(c), "from", "2026-11-02", "to", "2026-11-08");

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("&lt;img src=x onerror=alert(1)&gt;");
        assertThat(html).doesNotContain("<script").doesNotContain("<img").doesNotContain("<iframe");
    }

    @Test
    void noTextAClientTypedIsShown() throws Exception {
        String secret = "SECRET-TYPED-TEXT-9042";
        TestClient c = client();
        UUID listing = data.listing(c.id(), "ETSY", CREATED);
        data.finding(listing, "ERROR", "title_length", secret);
        UUID tm = data.trademark(c.id(), "ETSY", CREATED, UNTIL, "COMPLETE", "HIGH");
        data.trademarkFinding(tm, 0, "catalog_exact", "HIGH", "ZORVEX", secret);
        UUID variation = data.variation(c.id(), "ETSY", CREATED, UNTIL, "COMPLETE");
        data.variationFinding(variation, 0, "duplicate_sku", "WARNING", secret);
        UUID screening = data.screening(c.id(), "ETSY", CREATED, UNTIL, "COMPLETE", "COMPLETE", "LOW");
        data.screeningFinding(screening, 0, "unsupported_characters", "WARNING", secret);

        String html = page(userOf(c), "from", "2026-11-02", "to", "2026-11-08");

        assertThat(html).doesNotContain(secret).contains("title_length").contains("ZORVEX");
        for (UUID id : new UUID[] {listing, tm, variation, screening}) {
            assertThat(html).doesNotContain(id.toString());
        }
    }

    @Test
    void theBarLinksToTheReportAndMarksTheCurrentPage() throws Exception {
        String html = page(userOf(client()));

        assertThat(html).containsPattern("aria-current=\"page\"[^>]*>Report<").contains("href=\"/console/report\"");
        assertThat(mvc.perform(get("/console").with(signedIn(userOf(client())))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).contains("href=\"/console/report\"");
    }

    @Test
    void aVisitorWhoIsNotSignedInIsSentToTheLoginPage() throws Exception {
        mvc.perform(get("/console/report").param("from", "2026-11-02").param("to", "2026-11-08")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
