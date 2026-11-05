package com.thanhdatpb.listingguard.api.console.editor;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

/** The "Check listing" form: who may open it, what it shows per marketplace, its counters and the cost of a press. */
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
class ConsoleEditorFormIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ActiveRulesets rulesets;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    JavaMailSender mailSender;

    private ConsoleUser user(PlanCode plan) {
        TestClient client = PlanTestSupport.newClient(provisioning, clients, plan);
        return userOf(client);
    }

    private String page(ConsoleUser user, String marketplace) throws Exception {
        return mvc.perform(get("/console/check").param("marketplace", marketplace).with(signedIn(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The opening tag of the counter that belongs to a field. */
    private static String counterTag(String html, String fieldId) {
        Matcher matcher = Pattern.compile("<p[^>]*data-counter-for=\"" + fieldId + "\"[^>]*>").matcher(html);
        assertThat(matcher.find()).as("a counter for " + fieldId).isTrue();
        return matcher.group();
    }

    @Test
    void aVisitorWhoIsNotSignedInIsSentToTheLoginPage() throws Exception {
        mvc.perform(get("/console/check"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login"));
    }

    @Test
    void anUnknownMarketplaceIsRefusedAndNeverDefaulted() throws Exception {
        mvc.perform(get("/console/check").param("marketplace", "WALMART").with(signedIn(user(PlanCode.FREE))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theEtsyFormHasTitleTagsAndDescriptionWithCountersFromTheRuleset() throws Exception {
        RulesetConfig etsy = rulesets.get("ETSY").config();
        String html = page(user(PlanCode.FREE), "ETSY");

        assertThat(html).contains("name=\"title\"").contains("name=\"tags\"").contains("name=\"description\"")
                .doesNotContain("name=\"bulletPoints\"").doesNotContain("name=\"brand\"");
        assertThat(counterTag(html, "title"))
                .contains("data-limit=\"" + etsy.rule("title_length").intParam("max_length") + "\"");
        String tags = counterTag(html, "tags");
        assertThat(tags).contains("data-limit-count=\"" + etsy.rule("tag_count").intParam("max_count") + "\"")
                .contains("data-limit-each=\"" + etsy.rule("tag_length").intParam("max_length") + "\"");
    }

    @Test
    void theAmazonFormHasBulletPointsAndSearchTermsAndNoCounterForARuleThatIsSwitchedOff() throws Exception {
        RulesetConfig amazon = rulesets.get("AMAZON").config();
        String html = page(user(PlanCode.FREE), "AMAZON");

        assertThat(html).contains("name=\"bulletPoints\"").contains("name=\"searchTerms\"").contains("name=\"brand\"")
                .contains("name=\"productType\"").doesNotContain("name=\"tags\"").doesNotContain("name=\"whoMade\"");
        assertThat(counterTag(html, "title"))
                .contains("data-limit=\"" + amazon.rule("title_length").intParam("max_length") + "\"");
        String bullets = counterTag(html, "bulletPoints");
        assertThat(bullets).contains("data-limit-count=\"" + amazon.rule("bullet_count").intParam("max_count") + "\"");
        // bullet_length is switched off in the ruleset, so no per-bullet limit is invented
        assertThat(amazon.rule("bullet_length").enabled()).isFalse();
        assertThat(bullets).doesNotContain("data-limit-each");
        assertThat(counterTag(html, "searchTerms"))
                .contains("data-limit=\"" + amazon.rule("search_terms_length").intParam("max_bytes") + "\"")
                .contains("data-mode=\"bytes\"");
    }

    @Test
    void otherListingFieldsAreCollapsedByDefaultAndWhoMadeWhenMadeAreFreeTextWithAHintAndNoCodeList() throws Exception {
        String html = page(user(PlanCode.FREE), "ETSY");

        Matcher details = Pattern.compile("<details[^>]*id=\"other-fields\"[^>]*>").matcher(html);
        assertThat(details.find()).isTrue();
        assertThat(details.group()).doesNotContain(" open");
        assertThat(html).contains("Other listing fields").contains("name=\"price\"").contains("name=\"quantity\"")
                .contains("name=\"category\"").contains("name=\"whoMade\"").contains("name=\"whenMade\"");
        assertThat(html).contains("i_did").contains("made_to_order").contains("does not keep a list of codes");
        // free text: an input, not a choice of values
        assertThat(html).doesNotContain("<select").doesNotContain("<option");
    }

    @Test
    void theFormCarriesACsrfTokenAndLoadsOnlyItsOwnScript() throws Exception {
        String html = page(user(PlanCode.FREE), "ETSY");

        assertThat(html).contains("name=\"_csrf\"");
        assertThat(html).contains("/console/editor.js").contains("/console/editor.css");
        assertThat(html).doesNotContain("http://").doesNotContain("https://");
    }

    @Test
    void thePressOfCheckCostsOneUnitWithoutTheTrademarkPackAndTwoWithIt() throws Exception {
        int freeQuota = PlanTestSupport.quota(jdbc, PlanCode.FREE);
        ConsoleUser free = user(PlanCode.FREE);
        ConsoleUser seller = user(PlanCode.SELLER);

        String freeHtml = page(free, "ETSY");
        String sellerHtml = page(seller, "ETSY");

        assertThat(freeHtml).contains("Cost: up to <span>1</span>").contains("unit</span>")
                .contains("Remaining this month: <span>" + freeQuota + "</span> of")
                .contains("<time>2026-12-01</time>").doesNotContain("trademark 1");
        assertThat(sellerHtml).contains("Cost: up to <span>2</span>").contains("units</span>")
                .contains("listing rules 1 + trademark 1");
    }
}
