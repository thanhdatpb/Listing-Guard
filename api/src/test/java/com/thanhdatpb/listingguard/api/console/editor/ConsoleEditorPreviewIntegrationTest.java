package com.thanhdatpb.listingguard.api.console.editor;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER_15;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.HtmlUtils;

/**
 * The "Simulation" panel: labelled as a simulation, a search result cut by style and a product page that shows the whole title
 * with the part past the ruleset's limit set apart, and nothing of any marketplace's own look (no logo, no brand color, no domain).
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
class ConsoleEditorPreviewIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

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

    private ConsoleUser user() {
        return userOf(PlanTestSupport.newClient(provisioning, clients, PlanCode.FREE));
    }

    private String page(String marketplace) throws Exception {
        return mvc.perform(get("/console/check").param("marketplace", marketplace).with(signedIn(user())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String press(Map<String, String> form) throws Exception {
        return mvc.perform(checkPost(user(), form)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theEmptyPageAlreadyHasTheSimulationLabelForBothMarketplaces() throws Exception {
        for (String marketplace : new String[] {"ETSY", "AMAZON"}) {
            String html = page(marketplace);

            assertThat(html).as(marketplace).contains("id=\"preview\"").contains("<span class=\"badge\">Simulation</span>")
                    .contains("Your title appears here").contains("not a real page");
            assertThat(html).as(marketplace).contains("class=\"sim-card").contains("class=\"sim-page\"");
        }
    }

    @Test
    void theEtsyStyleCardIsATileAndTheAmazonStyleCardIsARow() throws Exception {
        assertThat(page("ETSY")).contains("sim-card tile").doesNotContain("sim-card row");
        assertThat(page("AMAZON")).contains("sim-card row").doesNotContain("sim-card tile");
    }

    @Test
    void theSearchResultShowsTheWholeTitleToBeCutByStyleAndTheProductPageMarksWhatIsBeyondTheLimit() throws Exception {
        int max = rulesets.get("ETSY").config().rule("title_length").intParam("max_length");
        String title = "w".repeat(max) + " BEYOND" + unique();

        String html = press(etsyForm(title));

        Matcher card = Pattern.compile("<p class=\"sim-title\">(.*?)</p>", Pattern.DOTALL).matcher(html);
        assertThat(card.find()).isTrue();
        assertThat(HtmlUtils.htmlUnescape(card.group(1))).isEqualTo(title);
        Matcher beyond = Pattern.compile("<mark class=\"beyond\">(.*?)</mark>", Pattern.DOTALL).matcher(html);
        assertThat(beyond.find()).isTrue();
        assertThat(HtmlUtils.htmlUnescape(beyond.group(1))).isEqualTo(title.substring(max));
        assertThat(html).contains("beyond the title limit of " + max + " characters");
    }

    @Test
    void aTitleWithinTheLimitHasNothingMarkedBeyondIt() throws Exception {
        String html = press(etsyForm("A short title " + unique()));

        assertThat(html).doesNotContain("class=\"beyond\"").doesNotContain("beyond the title limit");
    }

    @Test
    void theAmazonPageSimulationShowsBrandAndBulletPoints() throws Exception {
        Map<String, String> form = Map.of("marketplace", "AMAZON", "title", "Plain carry case " + unique(), "brand", "Acme",
                "bulletPoints", "light shell\nspinner wheels", "price", "89.99");

        String html = press(form);

        Matcher page = Pattern.compile("<div class=\"sim-page\">(.*?)</article>", Pattern.DOTALL).matcher(html);
        assertThat(page.find()).isTrue();
        assertThat(page.group(1)).contains("Acme").contains("light shell").contains("spinner wheels").contains("89.99");
    }

    @Test
    void thePanelNeverUsesAMarketplaceLogoBrandColorOrDomain() throws Exception {
        String html = page("ETSY") + page("AMAZON");
        String css = mvc.perform(get("/console/editor.css").with(signedIn(user()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().toLowerCase();

        assertThat(html.toLowerCase()).doesNotContain("<img").doesNotContain("<svg").doesNotContain("etsy.com")
                .doesNotContain("amazon.com").doesNotContain("http://").doesNotContain("https://");
        assertThat(css).doesNotContain("url(").doesNotContain("@import").doesNotContain("etsy").doesNotContain("amazon")
                // the orange and navy of the two marketplaces
                .doesNotContain("#f1641e").doesNotContain("#ff9900").doesNotContain("#232f3e").doesNotContain("#febd69")
                .doesNotContain("#f56400");
    }
}
