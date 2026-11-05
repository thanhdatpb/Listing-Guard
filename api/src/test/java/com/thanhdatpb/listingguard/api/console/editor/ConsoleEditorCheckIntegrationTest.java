package com.thanhdatpb.listingguard.api.console.editor;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.section;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER_15;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import java.util.List;
import java.util.Map;
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

/**
 * One press of "Check" with plans on and the synthetic trademark catalog loaded: what the page shows, what it costs and
 * what it leaves out. The two checks and their plan, quota and cache rules are the API's; these tests look at the page.
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
class ConsoleEditorCheckIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TrademarkRulesets trademarkRulesets;

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

    private int used(ConsoleUser user) {
        return PlanTestSupport.used(jdbc, user.clientId(), NOVEMBER);
    }

    @Test
    void aReadyListingWithAHighTrademarkRiskShowsBothSideBySideWithTheDisclaimer() throws Exception {
        String html = press(user(PlanCode.SELLER), etsyForm("Zorvex ceramic mug " + unique()));

        String readiness = section(html, "readiness-panel");
        String trademark = section(html, "trademark-panel");
        assertThat(readiness).contains("Ready").containsPattern("<p class=\"big\">\\d+</p>");
        assertThat(trademark).contains("<p class=\"big\">HIGH</p>").contains(trademarkRulesets.ruleset().disclaimer());
        // one container, readiness first and the trademark panel straight after it, both above any finding
        int verdicts = html.indexOf("class=\"verdicts\"");
        int readinessAt = html.indexOf("id=\"readiness-panel\"");
        int trademarkAt = html.indexOf("id=\"trademark-panel\"");
        assertThat(verdicts).isNotNegative().isLessThan(readinessAt);
        assertThat(readinessAt).isLessThan(trademarkAt);
        assertThat(html.substring(readinessAt, trademarkAt)).doesNotContain("<section class=\"findings\"");
        assertThat(trademarkAt).isLessThan(html.indexOf("id=\"findings-heading\""));
        // the evidence of the trademark finding is marked
        assertThat(html).contains("<mark>Zorvex</mark>");
    }

    @Test
    void findingsAreGroupedBySeverityErrorsFirstWithTheEvidenceMarkedAndAFixShown() throws Exception {
        Map<String, String> form = etsyForm("Plain ceramic mug " + unique());
        form.put("tags", "a very very long tag name\na very very long tag name");

        String html = press(user(PlanCode.FREE), form);

        String findings = section(html, "rule-findings");
        assertThat(findings.indexOf("<span>Errors</span>")).isNotNegative()
                .isLessThan(findings.indexOf("<span>Warnings</span>"));
        assertThat(findings).contains("<mark>a very very long tag name</mark>").contains("Suggested fix:")
                .contains("Proposed value:");
        assertThat(section(html, "readiness-panel")).contains("Not ready");
    }

    @Test
    void aPlanWithoutTheTrademarkPackSeesANoteAndAnUpgradeHintAndPaysOneUnit() throws Exception {
        ConsoleUser free = user(PlanCode.FREE);
        List<String> plansWithTrademark = jdbc.queryForList(
                "SELECT plan_code FROM plan_pack WHERE pack_id = 'trademark-risk' ORDER BY plan_code", String.class);

        String html = press(free, etsyForm("Free plan mug " + unique()));

        assertThat(section(html, "readiness-panel")).containsPattern("<p class=\"big\">\\d+</p>");
        String trademark = section(html, "trademark-panel");
        assertThat(trademark).contains("Trademark not checked").contains("does not include the trademark check")
                .contains("FREE").contains(trademarkRulesets.ruleset().disclaimer());
        assertThat(plansWithTrademark).isNotEmpty().allSatisfy(plan -> assertThat(trademark).contains(plan));
        assertThat(used(free)).isEqualTo(1);
    }

    @Test
    void aFullCheckCostsTwoUnitsAndTheSameListingAgainIsFree() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        Map<String, String> form = etsyForm("Seller mug " + unique());

        press(seller, form);
        assertThat(used(seller)).isEqualTo(2);

        String again = press(seller, form);
        assertThat(used(seller)).isEqualTo(2);
        assertThat(section(again, "readiness-panel")).contains("Ready");
        assertThat(section(again, "trademark-panel")).contains("Risk level");
    }

    @Test
    void aUsedUpQuotaShowsTheResetDateInBothPanelsAndNoScoreAndStoresNothing() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        PlanTestSupport.setUsed(jdbc, seller.clientId(), NOVEMBER, PlanTestSupport.quota(jdbc, PlanCode.SELLER));

        String html = press(seller, etsyForm("No quota mug " + unique()));

        String readiness = section(html, "readiness-panel");
        assertThat(readiness).contains("<p class=\"big\">Not scored</p>").contains("2026-12-01").doesNotContain("Ready");
        assertThat(section(html, "trademark-panel")).contains("Trademark not checked").contains("2026-12-01");
        assertThat(html).doesNotContain("id=\"rule-findings\"");
        assertThat(PlanTestSupport.countOf(jdbc, "listing_check", seller.clientId())).isZero();
    }

    @Test
    void aListingAlreadyCheckedIsStillAnsweredWhenTheQuotaIsUsedUp() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        Map<String, String> form = etsyForm("Cached mug " + unique());
        press(seller, form);
        PlanTestSupport.setUsed(jdbc, seller.clientId(), NOVEMBER, PlanTestSupport.quota(jdbc, PlanCode.SELLER));

        String html = press(seller, form);

        assertThat(section(html, "readiness-panel")).contains("Ready").doesNotContain("quota");
        assertThat(section(html, "trademark-panel")).doesNotContain("quota");
    }

    @Test
    void aRequiredFieldsFindingOpensTheOtherListingFields() throws Exception {
        Map<String, String> form = Map.of("marketplace", "ETSY", "title", "Only a title " + unique());

        String html = press(user(PlanCode.FREE), form);

        Matcher details = Pattern.compile("<details[^>]*id=\"other-fields\"[^>]*>").matcher(html);
        assertThat(details.find()).isTrue();
        assertThat(details.group()).contains(" open");
        assertThat(section(html, "rule-findings")).contains("required_fields");
    }

    @Test
    void aPriceThatCannotBeReadIsSentBackToItsFieldAndNothingIsCheckedOrCounted() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        Map<String, String> form = etsyForm("Typed title " + unique());
        form.put("price", "19,99");

        String html = press(seller, form);

        assertThat(html).contains("Enter the price as a number with a decimal point").contains("aria-invalid=\"true\"");
        assertThat(html).doesNotContain("id=\"readiness-panel\"").doesNotContain("id=\"trademark-panel\"");
        Matcher details = Pattern.compile("<details[^>]*id=\"other-fields\"[^>]*>").matcher(html);
        assertThat(details.find()).isTrue();
        assertThat(details.group()).contains(" open");
        assertThat(html).contains(form.get("title"));
        assertThat(PlanTestSupport.countOf(jdbc, "listing_check", seller.clientId())).isZero();
        assertThat(used(seller)).isZero();
    }

    @Test
    void whatTheSellerTypedComesBackEscapedNeverAsMarkup() throws Exception {
        Map<String, String> form = etsyForm("<script>alert('x')</script> " + unique());

        String html = press(user(PlanCode.FREE), form);

        assertThat(html).doesNotContain("<script>alert").contains("&lt;script&gt;alert");
    }

    @Test
    void thePagesThatCarryWhatWasTypedAreNeverCached() throws Exception {
        ConsoleUser free = user(PlanCode.FREE);

        String checked = mvc.perform(checkPost(free, etsyForm("Header mug " + unique()))).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Cache-Control");
        String form = mvc.perform(get("/console/check").with(signedIn(free))).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Cache-Control");

        assertThat(checked).contains("no-store");
        assertThat(form).contains("no-store");
    }

    @Test
    void aPressWithoutACsrfTokenIsRefused() throws Exception {
        ConsoleUser free = user(PlanCode.FREE);

        mvc.perform(post("/console/check").with(signedIn(free)).param("marketplace", "ETSY").param("title", "No token"))
                .andExpect(status().isForbidden());
        assertThat(PlanTestSupport.countOf(jdbc, "listing_check", free.clientId())).isZero();
    }

    @Test
    void anUnknownMarketplaceIsRefusedOnPostToo() throws Exception {
        mvc.perform(checkPost(user(PlanCode.FREE), Map.of("marketplace", "WALMART", "title", "x")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAmazonListingIsCheckedWithTheSameTwoPanels() throws Exception {
        Map<String, String> form = Map.of("marketplace", "AMAZON", "title", "Plain carry case " + unique(),
                "brand", "Acme", "bulletPoints", "light\nstrong", "productType", "LUGGAGE", "price", "89.99", "quantity", "3");

        String html = press(user(PlanCode.SELLER), form);

        assertThat(section(html, "readiness-panel")).containsPattern("<p class=\"big\">\\d+</p>");
        assertThat(section(html, "trademark-panel")).contains("Risk level");
    }
}
