package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.feedback.FindingFeedbackService;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The console side of finding feedback (ADR-034): the widget next to each finding of the editor, a press that saves and can be
 * changed (htmx answers the widget alone, without a script a page), another client's finding refused as a 404, the note limit, the
 * home page with and without data, and the statistics page and its CSV. Every name is invented.
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
class ConsoleFeedbackIntegrationTest {

    /** A title with several findings (too long, a repeated word, repeated special characters): indexes 0 and 1 exist. */
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

    @MockitoBean
    JavaMailSender mailSender;

    @BeforeEach
    void realTime() {
        // the check service stamps results with the system clock, so the home page's week must hold today
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private TestClient client() {
        return newClient(provisioning, clients, PlanCode.PRO);
    }

    /** A console user of a new client with an account row, as the sign-in leaves it. */
    private ConsoleUser member(TestClient client) {
        ConsoleUser user = userOf(client);
        jdbc.update("INSERT INTO account (email, client_id) VALUES (?, ?)", user.email(), client.id());
        return user;
    }

    private static String html(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** One press of "Check" in the editor; answers the id of the stored listing-rules result. */
    private UUID checkInEditor(ConsoleUser user, TestClient client) throws Exception {
        mvc.perform(checkPost(user, etsyForm(TITLE))).andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM listing_check WHERE client_id = ? ORDER BY created_at DESC LIMIT 1", UUID.class,
                client.id());
    }

    private MockHttpServletRequestBuilder press(ConsoleUser user, UUID checkId, int position, String verdict, String note,
                                                boolean htmx) {
        MockHttpServletRequestBuilder request = post("/console/feedback").with(signedIn(user)).with(csrf())
                .param("pack", "listing-rules").param("checkId", checkId.toString()).param("position", String.valueOf(position))
                .param("verdict", verdict);
        if (note != null) {
            request.param("note", note);
        }
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return request;
    }

    private int rows(TestClient client) {
        return jdbc.queryForObject("SELECT count(*) FROM finding_verdict WHERE client_id = ?", Integer.class, client.id());
    }

    @Test
    void theEditorShowsTheWidgetNextToEachFindingAndLoadsHtmxFromTheConsole() throws Exception {
        TestClient client = client();
        ConsoleUser user = member(client);

        String page = html(mvc.perform(checkPost(user, etsyForm(TITLE))).andExpect(status().isOk()));

        assertThat(page).contains("src=\"/console/vendor/htmx.min.js\"").contains("name=\"htmx-config\"")
                .contains("hx-post=\"/console/feedback\"").contains("name=\"verdict\" value=\"FALSE_POSITIVE\"")
                .contains("maxlength=\"" + FindingFeedbackService.MAX_NOTE_CHARS + "\"").contains("name=\"_csrf\"");
        assertThat(page).doesNotContain("unpkg").doesNotContain("cdn.");
        String js = html(mvc.perform(get("/console/vendor/htmx.min.js").with(signedIn(user))).andExpect(status().isOk()));
        assertThat(js).contains("htmx");
    }

    @Test
    void aPressSavesTheVerdictAndASecondPressChangesIt() throws Exception {
        TestClient client = client();
        ConsoleUser user = member(client);
        UUID checkId = checkInEditor(user, client);

        String first = html(mvc.perform(press(user, checkId, 0, "CORRECT", null, true)).andExpect(status().isOk()));
        assertThat(first.trim()).startsWith("<form").contains("value=\"CORRECT\" class=\"fb correct\" aria-pressed=\"true\"")
                .contains("class=\"fb-saved\"").doesNotContain("<html");

        String second = html(mvc.perform(press(user, checkId, 0, "FALSE_POSITIVE", "  a word of the product  ", true))
                .andExpect(status().isOk()));
        assertThat(second).contains("value=\"FALSE_POSITIVE\" class=\"fb false-positive\" aria-pressed=\"true\"")
                .contains("value=\"CORRECT\" class=\"fb correct\" aria-pressed=\"false\"").contains("a word of the product");

        assertThat(rows(client)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT verdict FROM finding_verdict WHERE client_id = ?", String.class, client.id()))
                .isEqualTo("FALSE_POSITIVE");
        // the editor shows the reader's own verdict when the same listing is checked again (the stored result is reused)
        String again = html(mvc.perform(checkPost(user, etsyForm(TITLE))).andExpect(status().isOk()));
        assertThat(again).contains("value=\"FALSE_POSITIVE\" class=\"fb false-positive\" aria-pressed=\"true\"");
    }

    @Test
    void withoutAScriptThePressAnswersAPageThatSaysWhatWasSaved() throws Exception {
        TestClient client = client();
        ConsoleUser user = member(client);
        UUID checkId = checkInEditor(user, client);

        String page = html(mvc.perform(press(user, checkId, 1, "NOT_USEFUL", null, false)).andExpect(status().isOk()));

        assertThat(page).contains("<html").contains("id=\"feedback-result\"").contains("Not useful");
        assertThat(rows(client)).isEqualTo(1);
    }

    @Test
    void anotherClientsFindingIsRefusedAsNotFoundAndNothingIsStored() throws Exception {
        TestClient owner = client();
        ConsoleUser ownerUser = member(owner);
        UUID checkId = checkInEditor(ownerUser, owner);
        TestClient other = client();
        ConsoleUser intruder = member(other);

        mvc.perform(press(intruder, checkId, 0, "FALSE_POSITIVE", null, true)).andExpect(status().isNotFound());
        mvc.perform(press(intruder, checkId, 0, "FALSE_POSITIVE", null, false)).andExpect(status().isNotFound());

        assertThat(rows(owner)).isZero();
        assertThat(rows(other)).isZero();
    }

    @Test
    void aNoteOverTheLimitIsRefusedWithTheReasonAndWhatWasTyped() throws Exception {
        TestClient client = client();
        ConsoleUser user = member(client);
        UUID checkId = checkInEditor(user, client);
        String note = "x".repeat(FindingFeedbackService.MAX_NOTE_CHARS + 5);

        String widget = html(mvc.perform(press(user, checkId, 0, "CORRECT", note, true)).andExpect(status().isBadRequest()));

        assertThat(widget).contains("class=\"fb-error\"").contains(String.valueOf(FindingFeedbackService.MAX_NOTE_CHARS + 5))
                .contains(note).doesNotContain("aria-pressed=\"true\"");
        assertThat(rows(client)).isZero();
    }

    @Test
    void aUserWithoutAnAccountGetsNoWidgetAndCannotAnswer() throws Exception {
        TestClient client = client();
        ConsoleUser stranger = userOf(client);
        String page = html(mvc.perform(checkPost(stranger, etsyForm(TITLE))).andExpect(status().isOk()));
        UUID checkId = jdbc.queryForObject("SELECT id FROM listing_check WHERE client_id = ? ORDER BY created_at DESC LIMIT 1",
                UUID.class, client.id());

        assertThat(page).doesNotContain("hx-post=\"/console/feedback\"");
        mvc.perform(press(stranger, checkId, 0, "CORRECT", null, true)).andExpect(status().isNotFound());
        assertThat(rows(client)).isZero();
    }

    @Test
    void theHomePageWithoutDataShowsHowToStart() throws Exception {
        ConsoleUser user = member(client());

        String page = html(mvc.perform(get("/console").with(signedIn(user))).andExpect(status().isOk()));

        assertThat(page).contains("id=\"home-empty\"").contains("href=\"/console/check\"").contains("href=\"/console/bulk\"")
                .contains("id=\"home-shortcuts\"").contains("href=\"/console/stats\"").contains("id=\"home-plan\"")
                .doesNotContain("id=\"home-cards\"").doesNotContain("<svg");
        assertThat(page).doesNotContain("<script");
    }

    @Test
    void theHomePageCountsTheWeekAndListsTheRulesMarkedFalsePositive() throws Exception {
        TestClient client = client();
        ConsoleUser user = member(client);
        UUID checkId = checkInEditor(user, client);
        mvc.perform(press(user, checkId, 0, "FALSE_POSITIVE", null, true)).andExpect(status().isOk());
        mvc.perform(press(user, checkId, 1, "CORRECT", null, true)).andExpect(status().isOk());
        String rule = jdbc.queryForObject("SELECT rule_id FROM finding_verdict WHERE client_id = ? AND verdict = 'FALSE_POSITIVE'",
                String.class, client.id());
        long withError = jdbc.queryForObject("SELECT count(DISTINCT c.id) FROM listing_check c JOIN finding f ON f.check_id = c.id "
                + "WHERE c.client_id = ? AND f.severity = 'ERROR'", Long.class, client.id());

        String page = html(mvc.perform(get("/console").with(signedIn(user))).andExpect(status().isOk()));

        assertThat(page).doesNotContain("id=\"home-empty\"").contains("id=\"home-cards\"").contains("<svg").contains("<rect")
                .doesNotContain("<script");
        assertThat(between(page, "id=\"card-checked\"", "</div>")).contains("class=\"card-value\">1<");
        assertThat(between(page, "id=\"card-error\"", "</div>")).contains("class=\"card-value\">" + withError + "<");
        assertThat(between(page, "id=\"card-feedback\"", "</div>")).contains("1 of 2 verdicts");
        assertThat(between(page, "id=\"home-top\"", "</section>")).contains(rule);
    }

    @Test
    void theStatisticsPageAndItsCsvShowTheRulesAndTheReviewers() throws Exception {
        TestClient client = client();
        ConsoleUser user = member(client);
        UUID checkId = checkInEditor(user, client);
        mvc.perform(press(user, checkId, 0, "FALSE_POSITIVE", "private-note-5530", true)).andExpect(status().isOk());

        String page = html(mvc.perform(get("/console/stats").with(signedIn(user))).andExpect(status().isOk()));
        assertThat(page).contains("id=\"stats-rule-table\"").contains(user.email()).contains("aria-current=\"page\"")
                .doesNotContain("private-note-5530");

        var csv = mvc.perform(get("/console/stats.csv").with(signedIn(user))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(csv.getContentType()).startsWith("text/csv");
        assertThat(csv.getHeader("Content-Disposition")).contains("attachment");
        String text = csv.getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿").contains(user.email()).doesNotContain("private-note-5530");

        mvc.perform(get("/console/stats").param("from", "2026-11-16").param("to", "2026-11-01").with(signedIn(user)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/console/stats")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    private static String between(String html, String start, String end) {
        int from = html.indexOf(start);
        assertThat(from).as(start).isNotNegative();
        int to = html.indexOf(end, from);
        return html.substring(from, to);
    }
}
