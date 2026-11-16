package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.withoutShell;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.history.CheckHistoryRecorder;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The history page and a stored result opened from it (ADR-033, ADR-041): the empty state, the row with its pills, its feedback state
 * and its link, the filter row (the period, the status, who checked, a part of a SKU) and its empty state, the pager and the findings of
 * an opened result as cards. Every name is invented; no listing text is stored, so none is looked for.
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
class ConsoleHistoryIntegrationTest {

    /** A title with several findings: too long, a repeated word, repeated special characters. */
    private static final String TITLE = "Synthetic mug mug mug!! " + "b".repeat(150);

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

    @Autowired
    CheckHistoryRecorder recorder;

    @MockitoBean
    JavaMailSender mailSender;

    @BeforeEach
    void realTime() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private TestClient client() {
        return newClient(provisioning, clients, PlanCode.PRO);
    }

    private ConsoleUser member(TestClient client) {
        ConsoleUser user = userOf(client);
        jdbc.update("INSERT INTO account (email, client_id) VALUES (?, ?)", user.email(), client.id());
        return user;
    }

    private static String html(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private UUID checkInEditor(ConsoleUser user, TestClient client, String title) throws Exception {
        mvc.perform(checkPost(user, etsyForm(title))).andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM listing_check WHERE client_id = ? ORDER BY created_at DESC LIMIT 1", UUID.class,
                client.id());
    }

    private String history(ConsoleUser user, String... params) throws Exception {
        var request = get("/console/history").with(signedIn(user));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return html(mvc.perform(request).andExpect(status().isOk()));
    }

    private static int countOf(String html, String text) {
        int count = 0;
        for (int at = html.indexOf(text); at >= 0; at = html.indexOf(text, at + text.length())) {
            count++;
        }
        return count;
    }

    private static int rowsOf(String page) {
        int start = page.indexOf("<tbody>");
        return start < 0 ? 0 : countOf(page.substring(start, page.indexOf("</tbody>")), "<tr>");
    }

    @Test
    void anEmptyHistoryInvitesToTheFirstCheckAndKeepsTheFilterRow() throws Exception {
        ConsoleUser user = member(client());

        String page = history(user);

        assertThat(page).contains("id=\"history-empty\"").contains("id=\"history-filters\"").doesNotContain("id=\"history-table\"")
                .doesNotContain("id=\"history-reset\"").doesNotContain("class=\"layout\"").doesNotContain("/console/console.css");
        assertThat(page).containsPattern("class=\"lg-btn lg-btn--primary\"[^>]*href=\"/console/bulk\"");
        assertThat(countOf(withoutShell(page), "lg-btn--primary")).as("one brand button on the screen").isEqualTo(1);
        assertThat(page).containsPattern("<h1[^>]*>Check history</h1>");
    }

    @Test
    void aRowHasItsPillsItsFeedbackStateAndALinkToTheResultWhoseFindingsAreCards() throws Exception {
        TestClient client = client();
        ConsoleUser user = member(client);
        UUID checkId = checkInEditor(user, client, TITLE);
        int findings = jdbc.queryForObject("SELECT count(*) FROM finding WHERE check_id = ?", Integer.class, checkId);
        assertThat(findings).isGreaterThan(1);

        String page = history(user);
        assertThat(page).contains("id=\"history-table\"").contains("<caption class=\"lg-sr-only\">").contains("lg-pill lg-pill--error")
                .contains(user.email()).contains("No feedback").contains("/console/history/listing-rules/" + checkId)
                .contains("Showing 1-" + rowsOf(page) + " of " + rowsOf(page) + " checks");
        assertThat(page).doesNotContain("rel=\"next\"");
        assertThat(countOf(page, "<th scope=\"col\"")).isGreaterThanOrEqualTo(9);

        mvc.perform(post("/console/feedback").with(signedIn(user)).with(csrf()).param("pack", "listing-rules")
                .param("checkId", checkId.toString()).param("position", "0").param("verdict", "FALSE_POSITIVE"))
                .andExpect(status().is2xxSuccessful());
        assertThat(history(user)).contains("1/" + findings + " with feedback").doesNotContain("No feedback");

        String result = html(mvc.perform(get("/console/history/listing-rules/" + checkId).with(signedIn(user))).andExpect(status().isOk()));
        assertThat(result).contains("id=\"history-facts\"").contains("id=\"history-findings\"").doesNotContain("class=\"layout\"")
                .doesNotContain("/console/console.css").contains("aria-pressed=\"true\"");
        assertThat(countOf(result, "<article class=\"lg-finding\"")).isEqualTo(findings);
        assertThat(result).contains("Findings (" + findings + ")").contains("lg-finding__title").contains("lg-finding__head");
    }

    @Test
    void theFilterRowNarrowsBySkuByPeriodByStatusAndByWhoChecked() throws Exception {
        TestClient client = client();
        ConsoleUser user = member(client);
        UUID account = jdbc.queryForObject("SELECT id FROM account WHERE email = ?", UUID.class, user.email());
        UUID first = checkInEditor(user, client, TITLE);
        recorder.account(client.id(), account, "listing-rules", first, "ETSY", 1, "MUG-0042");
        recorder.account(client.id(), account, "listing-rules", first, "ETSY", 1, "OLD-0001");
        jdbc.update("UPDATE check_history_event SET checked_at = ? WHERE sku = 'OLD-0001'",
                Timestamp.from(Instant.now().minus(120, ChronoUnit.DAYS)));

        // a part of a SKU, in any case
        String bySku = history(user, "sku", "mug-00");
        assertThat(rowsOf(bySku)).isEqualTo(1);
        assertThat(bySku).contains("MUG-0042").contains("value=\"mug-00\"").contains("id=\"history-reset\"").contains("type=\"search\"");
        // a wild card of SQL is a character, not a pattern
        assertThat(history(user, "sku", "%")).contains("id=\"history-empty\"").contains("No check matches these filters");
        assertThat(history(user, "sku", "_")).contains("id=\"history-empty\"");

        // the period counts back from today; "all" has the old one too
        String all = history(user);
        assertThat(all).contains("OLD-0001").contains("MUG-0042");
        String week = history(user, "period", "7");
        assertThat(week).doesNotContain("OLD-0001").contains("MUG-0042").containsPattern("<option value=\"7\" selected=\"selected\"");
        String custom = history(user, "from", "2000-01-01", "to", "2000-01-02");
        assertThat(custom).contains("id=\"history-empty\"").contains("2000-01-01 to 2000-01-02")
                .contains("<input type=\"hidden\" name=\"from\" value=\"2000-01-01\"");

        // the status, and who checked (the list is the people and keys that did)
        assertThat(history(user, "status", "FAILED")).contains("id=\"history-empty\"");
        assertThat(history(user, "status", "completed")).contains("MUG-0042");
        String who = history(user, "checker", user.email());
        assertThat(who).contains("<option value=\"" + user.email() + "\" selected=\"selected\"").contains("MUG-0042");
        assertThat(history(user, "checker", "nobody@example.test")).contains("id=\"history-empty\"");
        assertThat(all).contains("<option value=\"" + user.email() + "\"");

        // every control of the row has a name for a screen reader
        int start = all.indexOf("id=\"history-filters\"");
        String form = all.substring(start, all.indexOf("</form>", start));
        assertThat(countOf(form, "aria-label=")).isGreaterThanOrEqualTo(6);
    }

    @Test
    void anotherClientsChecksAreNeverInTheList() throws Exception {
        TestClient client = client();
        ConsoleUser user = member(client);
        TestClient other = client();
        ConsoleUser stranger = member(other);
        UUID account = jdbc.queryForObject("SELECT id FROM account WHERE email = ?", UUID.class, stranger.email());
        UUID theirs = checkInEditor(stranger, other, TITLE);
        recorder.account(other.id(), account, "listing-rules", theirs, "ETSY", 1, "THEIRS-7");

        String page = history(user, "sku", "THEIRS");

        assertThat(page).contains("id=\"history-empty\"").doesNotContain("THEIRS-7").doesNotContain(stranger.email());
        assertThat(history(user)).doesNotContain(stranger.email());
        mvc.perform(get("/console/history/listing-rules/" + theirs).with(signedIn(user))).andExpect(status().is4xxClientError());
    }

    @Test
    void theListIsPagedWithAPreviousAndANextAndAPageBeyondTheEndIsTheLastOne() throws Exception {
        TestClient client = client();
        ConsoleUser user = member(client);
        UUID account = jdbc.queryForObject("SELECT id FROM account WHERE email = ?", UUID.class, user.email());
        UUID check = checkInEditor(user, client, TITLE);
        for (int i = 0; i < 27; i++) {
            recorder.account(client.id(), account, "listing-rules", check, "ETSY", 1, "PAGED-" + i);
        }

        // the editor's own checks are in the list too; the SKU narrows it to the 27 that were seeded
        String first = history(user, "sku", "PAGED-");
        assertThat(rowsOf(first)).isEqualTo(25);
        assertThat(first).contains("Showing 1-25 of 27 checks").contains("Page 1 of 2").containsPattern("rel=\"next\"[^>]*href=\"[^\"]*page=1")
                .doesNotContain("rel=\"prev\"").containsPattern("aria-disabled=\"true\"[^>]*>Previous<");
        String second = history(user, "sku", "PAGED-", "page", "1");
        assertThat(rowsOf(second)).isEqualTo(2);
        assertThat(second).contains("Showing 26-27 of 27 checks").contains("rel=\"prev\"").doesNotContain("rel=\"next\"");
        Matcher link = Pattern.compile("rel=\"prev\"[^>]*href=\"([^\"]*)\"").matcher(second);
        assertThat(link.find()).isTrue();
        assertThat(link.group(1)).contains("/console/history").contains("page=0");
        assertThat(history(user, "sku", "PAGED-", "page", "99")).contains("Showing 26-27 of 27 checks");
        assertThat(history(user, "sku", "paged-1", "page", "0")).contains("Showing 1-11 of 11 checks");
    }
}
