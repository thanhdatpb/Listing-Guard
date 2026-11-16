package com.thanhdatpb.listingguard.api.console.bulk;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * What the work pages promise to a keyboard and a screen reader (ADR-040 section 6, ADR-041): a table has a caption that only a screen
 * reader sees, a feedback button says whether it is pressed, an icon that only decorates is hidden from a screen reader, the regions
 * htmx fills are polite live regions, a page has one level-one heading and the skip link's target, and the keyboard meets the form
 * before the result and the result before the preview. The pages are read as a browser receives them.
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
class ConsoleWorkPagesAccessibilityIntegrationTest {

    private static final String TITLE = "Zorvex mug mug mug!! " + "b".repeat(150);

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

    private ConsoleUser user;

    @BeforeEach
    void aSignedInAccount() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        TestClient client = newClient(provisioning, clients, PlanCode.PRO);
        user = userOf(client);
        jdbc.update("INSERT INTO account (email, client_id) VALUES (?, ?)", user.email(), client.id());
    }

    private static String html(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String editorResult() throws Exception {
        Map<String, String> form = new LinkedHashMap<>(etsyForm(TITLE));
        form.put("tags", "mug\nmug\nzorvex fan tee");
        return html(mvc.perform(checkPost(user, form)));
    }

    private String bulkResult() throws Exception {
        String text = "SKU\tTitle\tTags\tDescription\n" + "A-1\t" + TITLE + "\tmug, mug\tA synthetic mug.\n" + "A-2\tPlain mug\tmug\tA mug.";
        String mapping = html(mvc.perform(BulkPages.prepareRequest(user, "ETSY", text, "yes")));
        return html(mvc.perform(BulkPages.runRequest(user, mapping, Map.of(), Map.of(), false)));
    }

    private String editMode() throws Exception {
        String before = "{\"title\":\"" + TITLE + "\",\"description\":\"A mug.\",\"tags\":[\"mug\"],\"price\":\"5.00\",\"quantity\":1,"
                + "\"who_made\":\"i_did\",\"when_made\":\"made_to_order\",\"category\":\"Home\"}";
        String after = before.replace(TITLE, "Plain mug");
        return html(mvc.perform(post("/console/check/edit").with(signedIn(user)).with(csrf()).param("marketplace", "ETSY")
                .param("before", before).param("after", after)));
    }

    private static int count(String html, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(html);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** Every page below, read as a browser receives it; the home page is read after a check, so it has data. */
    private Map<String, String> pages() throws Exception {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("editor", editorResult());
        pages.put("home", html(mvc.perform(get("/console").with(signedIn(user)))));
        pages.put("bulk form", html(mvc.perform(get("/console/bulk").with(signedIn(user)))));
        pages.put("bulk result", bulkResult());
        pages.put("edit mode", editMode());
        return pages;
    }

    @Test
    void everyTableHasACaptionOnlyAScreenReaderSees() throws Exception {
        Map<String, String> pages = pages();
        for (Map.Entry<String, String> page : pages.entrySet()) {
            String html = withoutShell(page.getValue());
            assertThat(count(html, "<table")).as(page.getKey() + ": tables and their hidden captions")
                    .isEqualTo(count(html, "<caption class=\"lg-sr-only\">"));
        }
        assertThat(count(pages.get("bulk result"), "<table")).isPositive();
        assertThat(count(pages.get("home"), "<table")).isPositive();
    }

    @Test
    void anIconThatOnlyDecoratesIsHiddenFromAScreenReaderAndAChartHasAName() throws Exception {
        for (Map.Entry<String, String> page : pages().entrySet()) {
            Matcher svg = Pattern.compile("<svg[^>]*>").matcher(withoutShell(page.getValue()));
            while (svg.find()) {
                assertThat(svg.group()).as(page.getKey()).satisfiesAnyOf(tag -> assertThat(tag).contains("aria-hidden=\"true\""),
                        tag -> assertThat(tag).contains("role=\"img\"").contains("aria-labelledby="));
            }
            Matcher mark = Pattern.compile("<[a-z]+[^>]*class=\"lg-mark\"[^>]*>").matcher(page.getValue());
            while (mark.find()) {
                assertThat(mark.group()).as(page.getKey() + ": the red diamond").contains("aria-hidden=\"true\"");
            }
        }
    }

    @Test
    void aFeedbackButtonSaysWhetherItIsPressedAndItsStatusIsPolite() throws Exception {
        Map<String, String> pages = pages();
        for (String name : new String[] {"editor", "bulk result", "edit mode"}) {
            String html = pages.get(name);
            Matcher buttons = Pattern.compile("<button[^>]*name=\"verdict\"[^>]*>").matcher(html);
            int found = 0;
            while (buttons.find()) {
                found++;
                assertThat(buttons.group()).as(name).containsPattern("aria-pressed=\"(true|false)\"");
            }
            assertThat(found).as(name + ": feedback buttons").isPositive();
            assertThat(count(html, "role=\"status\" aria-live=\"polite\"")).as(name + ": live status").isPositive();
        }
    }

    @Test
    void theRegionsThatAreFilledAfterwardsArePoliteLiveRegions() throws Exception {
        Map<String, String> pages = pages();
        assertThat(pages.get("editor")).containsPattern("<aside[^>]*id=\"results\"[^>]*aria-live=\"polite\"");
        assertThat(pages.get("bulk result")).containsPattern("id=\"copy-status\" role=\"status\" aria-live=\"polite\"");
        assertThat(pages.get("edit mode")).containsPattern("<section[^>]*id=\"edit-result\"[^>]*aria-live=\"polite\"");
    }

    @Test
    void aPageHasOneMainHeadingTheSkipTargetAndTheMeterHasAName() throws Exception {
        Map<String, String> pages = pages();
        for (Map.Entry<String, String> page : pages.entrySet()) {
            String html = page.getValue();
            assertThat(count(html, "<h1[ >]")).as(page.getKey() + ": level-one headings").isEqualTo(1);
            assertThat(html).as(page.getKey()).contains("href=\"#main\"").contains("<span id=\"main\" tabindex=\"-1\"></span>");
            assertThat(html.indexOf("class=\"app-skip\"")).as(page.getKey() + ": the skip link comes first")
                    .isLessThan(html.indexOf("<main"));
        }
        Matcher progress = Pattern.compile("<progress[^>]*>").matcher(pages.get("editor"));
        assertThat(progress.find()).isTrue();
        assertThat(progress.group()).contains("aria-label=").contains("max=\"100\"");
    }

    @Test
    void theKeyboardMeetsTheFormBeforeTheResultAndTheResultBeforeThePreview() throws Exception {
        Map<String, String> pages = pages();
        String editor = pages.get("editor");
        assertThat(editor.indexOf("id=\"editor-form\"")).isLessThan(editor.indexOf("id=\"check-button\""));
        assertThat(editor.indexOf("id=\"check-button\"")).isLessThan(editor.indexOf("name=\"apply\""));
        assertThat(editor.indexOf("id=\"results\"")).isLessThan(editor.indexOf("id=\"listing-card\""));
        assertThat(editor.indexOf("id=\"trademark-panel\"")).isLessThan(editor.indexOf("id=\"rule-findings\""));
        assertThat(editor.indexOf("id=\"trademark-findings\"")).isLessThan(editor.indexOf("id=\"legal-note\""));
        assertThat(editor.indexOf("id=\"legal-note\"")).isLessThan(editor.indexOf("id=\"preview\""));

        String bulk = pages.get("bulk form");
        assertThat(bulk.indexOf("id=\"tab-paste\"")).isLessThan(bulk.indexOf("id=\"paste-marketplace\""));
        assertThat(bulk.indexOf("id=\"paste-marketplace\"")).isLessThan(bulk.indexOf("id=\"text\""));
        assertThat(bulk.indexOf("id=\"text\"")).isLessThan(bulk.indexOf("lg-btn lg-btn--primary"));

        // a row, then its details link, and the detail it opens right after the row, before the next row
        String result = pages.get("bulk result");
        assertThat(result.indexOf("id=\"row-1\"")).isLessThan(result.indexOf("href=\"#bulk-detail-1\""));
        assertThat(result.indexOf("href=\"#bulk-detail-1\"")).isLessThan(result.indexOf("id=\"bulk-detail-1\""));
        assertThat(result.indexOf("id=\"bulk-detail-1\"")).isLessThan(result.indexOf("id=\"row-2\""));
        assertThat(result).containsPattern("aria-label=\"[^\"]+\"[^>]*>[^<]+</a></td>");
    }

    @Test
    void everyStatePillIsAWordOrACountAndTheFindingCardIsTheSameOnEveryPage() throws Exception {
        Map<String, String> pages = pages();
        for (String name : new String[] {"editor", "bulk result", "edit mode"}) {
            String html = pages.get(name);
            assertThat(count(html, "class=\"lg-finding\"")).as(name + ": finding cards").isPositive();
            Matcher pills = Pattern.compile("<span class=\"lg-pill lg-pill--(?:error|warn|trademark|clean)\">([^<]*)</span>").matcher(html);
            while (pills.find()) {
                assertThat(pills.group(1)).as(name + ": a state pill says a word or a count").isNotBlank();
            }
        }
    }
}
