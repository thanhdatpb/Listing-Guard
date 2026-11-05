package com.thanhdatpb.listingguard.api.console.editor;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER_15;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.HtmlUtils;

/**
 * "Apply suggestion": it only fills the form field with the proposed value. The test presses the buttons the way a browser
 * does: the editor form as it is now, plus every hidden {@code fix.<id>.*} value of the page, plus the button's own value.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test",
        "listing-guard.plans.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class ConsoleEditorApplyIntegrationTest {

    private static final String LONG_TITLE = "Handmade ceramic coffee mug ".repeat(8).trim();

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ActiveRulesets rulesets;

    @Autowired
    MutableClock clock;

    @MockitoBean
    JavaMailSender mailSender;

    @BeforeEach
    void startInTheMiddleOfNovember() {
        clock.set(NOVEMBER_15);
    }

    private ConsoleUser user(PlanCode plan) {
        return userOf(PlanTestSupport.newClient(provisioning, clients, plan));
    }

    private String press(ConsoleUser user, Map<String, String> form) throws Exception {
        return mvc.perform(checkPost(user, form)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** What a browser sends when the button of fix {@code id} is pressed: the form, all hidden fix values, the button. */
    private MockHttpServletRequestBuilder apply(ConsoleUser user, Map<String, String> form, String page, String id) {
        MockHttpServletRequestBuilder request = post("/console/check/apply").with(signedIn(user)).with(csrf());
        form.forEach(request::param);
        fixes(page).forEach((fixId, values) -> values.forEach((key, value) -> request.param("fix." + fixId + "." + key, value)));
        return request.param("apply", id);
    }

    private String applied(ConsoleUser user, Map<String, String> form, String page, String id) throws Exception {
        return mvc.perform(apply(user, form, page, id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** The hidden {@code fix.<id>.<key>} inputs of a page, by id. */
    private static Map<String, Map<String, String>> fixes(String html) {
        Map<String, Map<String, String>> fixes = new TreeMap<>();
        Matcher tags = Pattern.compile("<input[^>]*>").matcher(html);
        while (tags.find()) {
            String name = attribute(tags.group(), "name");
            if (name != null && name.startsWith("fix.")) {
                String[] parts = name.split("\\.");
                fixes.computeIfAbsent(parts[1], key -> new LinkedHashMap<>()).put(parts[2], attribute(tags.group(), "value"));
            }
        }
        return fixes;
    }

    private static String idOfFix(String html, String field, String action) {
        return fixes(html).entrySet().stream()
                .filter(entry -> field.equals(entry.getValue().get("field")) && action.equals(entry.getValue().get("action")))
                .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new AssertionError("no " + action + " fix for " + field));
    }

    private static String attribute(String tag, String name) {
        Matcher matcher = Pattern.compile("\\s" + name + "=\"([^\"]*)\"").matcher(tag);
        return matcher.find() ? HtmlUtils.htmlUnescape(matcher.group(1)) : null;
    }

    private static String inputValue(String html, String id) {
        Matcher matcher = Pattern.compile("<input[^>]*id=\"" + id + "\"[^>]*>").matcher(html);
        assertThat(matcher.find()).as("input " + id).isTrue();
        return attribute(matcher.group(), "value");
    }

    private static String textarea(String html, String id) {
        Matcher matcher = Pattern.compile("<textarea[^>]*id=\"" + id + "\"[^>]*>(.*?)</textarea>", Pattern.DOTALL).matcher(html);
        assertThat(matcher.find()).as("textarea " + id).isTrue();
        return HtmlUtils.htmlUnescape(matcher.group(1));
    }

    private int stored(ConsoleUser user) {
        return PlanTestSupport.countOf(jdbc, "listing_check", user.clientId())
                + PlanTestSupport.countOf(jdbc, "trademark_check", user.clientId());
    }

    private int used(ConsoleUser user) {
        return PlanTestSupport.used(jdbc, user.clientId(), NOVEMBER);
    }

    @Test
    void applyingATitleFixFillsTheFieldWithTheProposedValueAndNeverChecksAgain() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        Map<String, String> form = etsyForm(LONG_TITLE + " " + unique());
        String page = press(seller, form);
        String id = idOfFix(page, "title", "REPLACE");
        String proposed = fixes(page).get(id).get("proposed");
        int storedBefore = stored(seller);
        int usedBefore = used(seller);

        String after = applied(seller, form, page, id);

        assertThat(inputValue(after, "title")).isEqualTo(proposed);
        // the simulation already shows the applied title, although nothing was checked
        Matcher card = Pattern.compile("<p class=\"sim-title\">(.*?)</p>", Pattern.DOTALL).matcher(after);
        assertThat(card.find()).isTrue();
        assertThat(HtmlUtils.htmlUnescape(card.group(1))).isEqualTo(proposed);
        assertThat(codePoints(proposed)).isLessThanOrEqualTo(rulesets.get("ETSY").config().rule("title_length").intParam("max_length"));
        assertThat(after).contains("Replaced the title with the suggested value").contains("press Check");
        // nothing was checked, stored or counted, and the old result is not shown beside the changed text
        assertThat(stored(seller)).isEqualTo(storedBefore);
        assertThat(used(seller)).isEqualTo(usedBefore);
        assertThat(after).doesNotContain("id=\"readiness-panel\"").doesNotContain("id=\"trademark-panel\"")
                .doesNotContain("id=\"rule-findings\"");
        // the rest of the form is what the seller had typed
        assertThat(textarea(after, "description")).isEqualTo(form.get("description"));
        assertThat(textarea(after, "tags")).isEqualTo(form.get("tags"));
        assertThat(inputValue(after, "price")).isEqualTo("19.99");
        assertThat(inputValue(after, "whoMade")).isEqualTo("i_did");
    }

    @Test
    void tagFixesAreAppliedByValueOneAtATimeAndTheOtherTagsAreKept() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        Map<String, String> form = etsyForm("Plain mug " + unique());
        form.put("tags", "a very very long tag name\nshort\na very very long tag name");
        String page = press(seller, form);
        String remove = idOfFix(page, "tags[2]", "REMOVE");
        String replace = idOfFix(page, "tags[0]", "REPLACE");
        String proposed = fixes(page).get(replace).get("proposed");
        int storedBefore = stored(seller);

        // the repeated tag goes, the first occurrence stays
        String afterRemove = applied(seller, form, page, remove);
        assertThat(textarea(afterRemove, "tags")).isEqualTo("a very very long tag name\nshort");
        assertThat(afterRemove).contains("Removed the tag");

        // the next one is applied to the form as it is now, still found by its value
        form.put("tags", textarea(afterRemove, "tags"));
        String afterReplace = applied(seller, form, page, replace);
        assertThat(textarea(afterReplace, "tags")).isEqualTo(proposed + "\nshort");
        assertThat(afterReplace).contains("Replaced the tag");
        assertThat(stored(seller)).isEqualTo(storedBefore);
    }

    @Test
    void aFieldThatChangedAfterTheCheckIsNotOverwritten() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        Map<String, String> form = etsyForm(LONG_TITLE + " " + unique());
        String page = press(seller, form);
        String id = idOfFix(page, "title", "REPLACE");
        form.put("title", "A title typed after the check");

        String after = applied(seller, form, page, id);

        assertThat(inputValue(after, "title")).isEqualTo("A title typed after the check");
        assertThat(after).contains("changed since the check").contains("role=\"alert\"");
    }

    @Test
    void aSuggestionThatIsNotOnThePageAsksToCheckAgainAndChangesNothing() throws Exception {
        ConsoleUser free = user(PlanCode.FREE);
        Map<String, String> form = etsyForm("Typed title " + unique());

        String after = mvc.perform(post("/console/check/apply").with(signedIn(free)).with(csrf()).param("apply", "99")
                        .param("marketplace", "ETSY").param("title", form.get("title")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(after).contains("no longer available");
        assertThat(inputValue(after, "title")).isEqualTo(form.get("title"));
        assertThat(stored(free)).isZero();
    }

    @Test
    void theButtonsBelongToTheEditorFormSoWhatIsTypedNowIsWhatIsSent() throws Exception {
        String page = press(user(PlanCode.FREE), etsyForm(LONG_TITLE + " " + unique()));

        assertThat(page).contains("id=\"editor-form\"");
        Matcher buttons = Pattern.compile("<button[^>]*name=\"apply\"[^>]*>").matcher(page);
        int count = 0;
        while (buttons.find()) {
            count++;
            assertThat(buttons.group()).contains("form=\"editor-form\"").contains("formaction=\"/console/check/apply\"");
        }
        assertThat(count).isEqualTo(fixes(page).size()).isPositive();
        // the main button is the first submit button of the form, so pressing Enter in a field runs Check and not a fix
        assertThat(page.indexOf("id=\"check-button\"")).isLessThan(page.indexOf("name=\"apply\""));
    }

    @Test
    void applyingNeedsACsrfToken() throws Exception {
        ConsoleUser free = user(PlanCode.FREE);

        mvc.perform(post("/console/check/apply").with(signedIn(free)).param("apply", "0").param("marketplace", "ETSY"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownMarketplaceIsRefused() throws Exception {
        mvc.perform(post("/console/check/apply").with(signedIn(user(PlanCode.FREE))).with(csrf()).param("apply", "0")
                        .param("marketplace", "WALMART"))
                .andExpect(status().isBadRequest());
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }
}
