package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.section;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The console in both languages (ADR-028), with the default left at English: every page renders in each, a reader's choice is a
 * cookie, the rule explanations and the disclaimer follow the language, and what a finding says does not. The sentences asserted
 * are the ones in {@code messages*.properties}.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleLocalizationIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    TrademarkRulesets trademarkRulesets;

    @MockitoBean
    JavaMailSender mailSender;

    private TestClient client() {
        return PlanTestSupport.newClient(provisioning, clients, PlanCode.FREE);
    }

    private static MockHttpServletRequestBuilder in(MockHttpServletRequestBuilder request, String language) {
        return language == null ? request : request.cookie(new Cookie(ConsoleLocaleResolver.COOKIE, language));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, int status) throws Exception {
        return mvc.perform(request).andExpect(status().is(status)).andReturn();
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return perform(request, 200).getResponse().getContentAsString();
    }

    private static void assertRendered(String html, String language, String page) {
        assertThat(html).as(page + " " + language).contains("<html lang=\"" + language + "\"").doesNotContain("??")
                .doesNotContain("#{").doesNotContain("${");
    }

    @Test
    void theDefaultIsEnglishAndEveryPageSaysWhichLanguageItIsIn() throws Exception {
        String login = html(get("/login"));

        assertRendered(login, "en", "login");
        assertThat(login).contains("Sign in to Listing Guard").doesNotContain("Đăng nhập");
    }

    @Test
    void theLanguageLinksAreOnTheSignInPagesAndOnEveryPageOfTheBar() throws Exception {
        TestClient client = client();
        ConsoleUser user = userOf(client);
        String keyId = provisioning.activeKeys(client.id()).get(0).getKeyId();

        for (String page : List.of(html(get("/login")), html(get("/login/verify").param("token", "not-a-token")),
                html(get("/console").with(signedIn(user))), html(get("/console/api-keys").with(signedIn(user))),
                html(get("/console/api-keys/" + keyId + "/revoke").with(signedIn(user))),
                html(get("/console/report").with(signedIn(user))))) {
            assertThat(page).contains("English").contains("Tiếng Việt").containsPattern("hreflang=\"vi\"");
            assertThat(page).containsPattern("aria-current=\"true\"[^>]*>English<");
        }
        assertThat(html(get("/login"))).contains("href=\"/login?lang=en\"").contains("href=\"/login?lang=vi\"");
    }

    @Test
    void theSwitchKeepsTheMarketplaceOfTheEditorAndAPostPointsBackToTheForm() throws Exception {
        ConsoleUser user = userOf(client());

        // the marketplace is in the query string, as a browser sends it (the links are built from the query string)
        String amazon = html(get("/console/check?marketplace=AMAZON").with(signedIn(user)));
        String pressed = html(checkPost(user, etsyForm("Mug " + unique())));

        assertThat(amazon).contains("href=\"/console/check?marketplace=AMAZON&amp;lang=vi\"");
        assertThat(pressed).contains("href=\"/console/check?lang=vi\"");
    }

    @Test
    void aLinkSetsTheCookieAndThePageIsInThatLanguage() throws Exception {
        MvcResult result = perform(get("/login").param("lang", "vi"), 200);
        String page = result.getResponse().getContentAsString();

        assertRendered(page, "vi", "login");
        assertThat(page).contains("Đăng nhập Listing Guard").doesNotContain("Sign in to Listing Guard");
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).hasSize(1).first().asString()
                .startsWith("lg_lang=vi;").contains("HttpOnly").contains("SameSite=Lax");
    }

    @Test
    void theCookieKeepsTheLanguageOnTheNextPagesAndAPlainRequestSetsNoCookie() throws Exception {
        MvcResult result = perform(in(get("/login"), "vi"), 200);

        assertThat(result.getResponse().getContentAsString()).contains("Đăng nhập Listing Guard");
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(cookie -> cookie.startsWith("lg_lang"));
        assertThat(html(in(get("/login").param("lang", "en"), "vi"))).contains("Sign in to Listing Guard");
    }

    @Test
    void aLanguageThatIsNotOfferedChangesNothingAndSetsNoCookie() throws Exception {
        MvcResult result = perform(in(get("/login").param("lang", "fr"), "vi"), 200);

        assertThat(result.getResponse().getContentAsString()).contains("Đăng nhập Listing Guard");
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(cookie -> cookie.startsWith("lg_lang"));
        assertThat(html(get("/login").param("lang", "fr"))).contains("Sign in to Listing Guard");
    }

    @Test
    void theBrowsersAcceptLanguageDoesNotChangeAConsolePage() throws Exception {
        assertThat(html(get("/login").header(HttpHeaders.ACCEPT_LANGUAGE, "vi"))).contains("Sign in to Listing Guard");
    }

    @Test
    void everyPageRendersInBothLanguagesAndAnswersInOnlyOneOfThem() throws Exception {
        TestClient client = client();
        ConsoleUser user = userOf(client);
        String keyId = provisioning.activeKeys(client.id()).get(0).getKeyId();
        Map<String, String> signOut = Map.of("en", "Sign out", "vi", "Đăng xuất");

        for (String language : List.of("en", "vi")) {
            String other = language.equals("en") ? "vi" : "en";
            Map<String, MockHttpServletRequestBuilder> pages = new LinkedHashMap<>();
            pages.put("login", get("/login"));
            pages.put("verify (refused)", get("/login/verify").param("token", "not-a-token"));
            pages.put("dashboard", get("/console").with(signedIn(user)));
            pages.put("check (Etsy)", get("/console/check").param("marketplace", "ETSY").with(signedIn(user)));
            pages.put("check (Amazon)", get("/console/check").param("marketplace", "AMAZON").with(signedIn(user)));
            pages.put("report", get("/console/report").with(signedIn(user)));
            pages.put("keys", get("/console/api-keys").with(signedIn(user)));
            pages.put("revoke", get("/console/api-keys/" + keyId + "/revoke").with(signedIn(user)));
            pages.put("a result", checkPost(user, etsyForm("a".repeat(150))));
            pages.put("a new key", post("/console/api-keys").with(signedIn(user)).with(csrf()).param("label", "x"));
            for (Map.Entry<String, MockHttpServletRequestBuilder> page : pages.entrySet()) {
                String body = html(in(page.getValue(), language));

                assertRendered(body, language, page.getKey());
                if (!page.getKey().equals("login") && !page.getKey().equals("verify (refused)")) {
                    assertThat(body).as(page.getKey() + " " + language).contains(signOut.get(language))
                            .doesNotContain(signOut.get(other));
                }
            }
        }
    }

    @Test
    void aFindingShowsWhatItsRuleChecksInTheLanguageOfThePageWhileTheFindingItselfIsUnchanged() throws Exception {
        ConsoleUser user = userOf(client());
        Map<String, String> form = etsyForm("a".repeat(150));

        String english = section(html(in(checkPost(user, form), "en")), "rule-findings");
        String vietnamese = section(html(in(checkPost(user, form), "vi")), "rule-findings");

        assertThat(english).contains("<code>title_length</code>").contains("What this rule checks:")
                .contains("Checks that the title is not longer than the title limit in the ruleset.");
        assertThat(vietnamese).contains("<code>title_length</code>").contains("Quy tắc này kiểm tra:")
                .contains("Kiểm tra tiêu đề không dài quá giới hạn tiêu đề trong bộ quy tắc.");
        // the API's own sentence about this listing: the same text, whatever the language of the page
        assertThat(english).contains("Tiêu đề dài 150 ký tự");
        assertThat(vietnamese).contains("Tiêu đề dài 150 ký tự");
        assertThat(vietnamese).doesNotContain("What this rule checks:");
    }

    @Test
    void theRuleIdIsShownOnceAndNeverAsAMessageCode() throws Exception {
        ConsoleUser user = userOf(client());

        String findings = section(html(in(checkPost(user, etsyForm("a".repeat(150))), "vi")), "rule-findings");

        assertThat(findings).containsOnlyOnce("<code>title_length</code>");
        assertThat(findings).doesNotContain("rule.title_length");
    }

    @Test
    void theTrademarkDisclaimerIsAlwaysTheRulesetsWordsAndAnEnglishStatementComesFirstOnAnEnglishPage() throws Exception {
        String disclaimer = trademarkRulesets.ruleset().disclaimer();
        ConsoleUser user = userOf(client());
        Map<String, String> form = etsyForm("Mug " + unique());

        String english = section(html(in(checkPost(user, form), "en")), "trademark-panel");
        String vietnamese = section(html(in(checkPost(user, form), "vi")), "trademark-panel");

        assertThat(english).contains("id=\"trademark-disclaimer-note\"")
                .contains("These results are reference warnings only, not a legal conclusion")
                .contains("Original text of the ruleset:").contains(disclaimer);
        assertThat(english.indexOf("These results are reference warnings only")).isLessThan(english.indexOf(disclaimer));
        assertThat(vietnamese).contains(disclaimer).doesNotContain("trademark-disclaimer-note").doesNotContain("Nguyên văn")
                .doesNotContain("reference warnings only");
        assertThat(vietnamese.split(Pattern.quote(disclaimer), -1)).as("said once").hasSize(2);
    }

    @Test
    void theReportPageCarriesTheSameDisclaimerRules() throws Exception {
        String disclaimer = trademarkRulesets.ruleset().disclaimer();
        ConsoleUser user = userOf(client());

        String english = html(in(get("/console/report").with(signedIn(user)), "en"));
        String vietnamese = html(in(get("/console/report").with(signedIn(user)), "vi"));

        assertThat(english).contains(disclaimer).contains("Original text of the ruleset:").contains("reference warnings only");
        assertThat(vietnamese).contains(disclaimer).doesNotContain("Original text of the ruleset:").doesNotContain("Nguyên văn");
    }

    @Test
    void theSentencesTheEditorsJavaBuildsFollowTheLanguage() throws Exception {
        ConsoleUser user = userOf(client());
        Map<String, String> badPrice = etsyForm("Typed " + unique());
        badPrice.put("price", "19,99");

        assertThat(html(in(checkPost(user, badPrice), "en"))).contains("Enter the price as a number with a decimal point");
        assertThat(html(in(checkPost(user, badPrice), "vi"))).contains("Nhập giá là một số có dấu chấm thập phân")
                .doesNotContain("Enter the price");

        MockHttpServletRequestBuilder apply = post("/console/check/apply").with(signedIn(user)).with(csrf()).param("apply", "0");
        etsyForm("Typed " + unique()).forEach(apply::param);
        assertThat(html(in(apply, "en"))).contains("That suggestion is no longer available");
        MockHttpServletRequestBuilder applyVietnamese = post("/console/check/apply").with(signedIn(user)).with(csrf())
                .param("apply", "0");
        etsyForm("Typed " + unique()).forEach(applyVietnamese::param);
        assertThat(html(in(applyVietnamese, "vi"))).contains("Gợi ý đó không còn dùng được").contains("role=\"alert\"");
    }

    @Test
    void theReportsRefusalsAreWrittenInTheLanguageOfThePageAndTheApisOwnSentenceIsNotShown() throws Exception {
        ConsoleUser user = userOf(client());
        // a request builder keeps every cookie it is given, so each language gets a builder of its own
        Function<String, MockHttpServletRequestBuilder> backwards = language -> in(
                get("/console/report").param("from", "2026-11-08").param("to", "2026-11-02").with(signedIn(user)), language);
        Function<String, MockHttpServletRequestBuilder> tooLong = language -> in(
                get("/console/report").param("from", "2026-01-01").param("to", "2026-12-31").with(signedIn(user)), language);
        Function<String, MockHttpServletRequestBuilder> noEnd = language -> in(
                get("/console/report").param("from", "2026-11-02").with(signedIn(user)), language);
        Function<String, MockHttpServletRequestBuilder> market = language -> in(
                get("/console/report").param("from", "2026-11-02").param("to", "2026-11-08").param("marketplace", "NOPE")
                        .with(signedIn(user)), language);

        assertThat(perform(backwards.apply("en"), 400).getResponse().getContentAsString()).contains("from must not be after to.");
        assertThat(perform(backwards.apply("vi"), 400).getResponse().getContentAsString()).contains("from không được sau to.")
                .doesNotContain("must not be after");
        assertThat(perform(tooLong.apply("vi"), 400).getResponse().getContentAsString()).contains("vượt quá")
                .contains("Hãy chọn khoảng ngắn hơn");
        assertThat(perform(noEnd.apply("vi"), 400).getResponse().getContentAsString()).contains("to là bắt buộc");
        assertThat(perform(market.apply("vi"), 400).getResponse().getContentAsString()).contains("Không có sàn này")
                .contains("ETSY").doesNotContain("NOPE");
        assertThat(perform(market.apply("en"), 400).getResponse().getContentAsString()).contains("Unknown marketplace")
                .doesNotContain("NOPE");
    }

    @Test
    void theReportsCoverageNoteIsWrittenInTheLanguageOfThePage() throws Exception {
        ConsoleUser user = userOf(client());

        assertThat(section(html(in(get("/console/report").with(signedIn(user)), "en")), "coverage"))
                .containsPattern("Every pack covers the whole period|retention of some packs");
        assertThat(section(html(in(get("/console/report").with(signedIn(user)), "vi")), "coverage"))
                .containsPattern("Mọi pack đều bao phủ cả khoảng thời gian|mức lưu giữ");
    }

    @Test
    void theApiKeysPagesSayTheirSentencesInTheLanguageOfThePage() throws Exception {
        TestClient client = client();
        ConsoleUser user = userOf(client);
        String keyId = provisioning.activeKeys(client.id()).get(0).getKeyId();

        String vietnamese = html(in(post("/console/api-keys").with(signedIn(user)).with(csrf()).param("label", "x"), "vi"));
        MockHttpServletRequestBuilder revoke = post("/console/api-keys/" + keyId + "/revoke").with(signedIn(user)).with(csrf());
        MvcResult revoked = perform(in(revoke, "vi"), 302);

        assertThat(vietnamese).contains("Khóa mới của bạn").contains("Sao chép").doesNotContain("Your new key");
        assertThat(revoked.getFlashMap().get("notice"))
                .isEqualTo("Đã thu hồi khóa " + keyId + ". Các ứng dụng đang dùng khóa này sẽ bị từ chối từ bây giờ.");
    }

    @Test
    void theSignInRequestAnswersAndSendsItsMailInTheLanguageOfThePage() throws Exception {
        String email = "vi-" + UUID.randomUUID() + "@example.test";

        String page = html(in(post("/login").with(csrf()).param("email", email), "vi"));

        assertThat(page).contains("Nếu có thể tạo hoặc đăng nhập tài khoản cho địa chỉ này");
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getSubject()).isEqualTo("Liên kết đăng nhập Listing Guard của bạn");
        assertThat(mail.getValue().getText()).contains("15 phút").contains("https://console.example.test/login/verify?token=");
        assertThat(html(in(post("/login").with(csrf()).param("email", "not an email"), "vi")))
                .contains("Hãy nhập địa chỉ email hợp lệ.");
    }

    @Test
    void theApisAnswersDoNotFollowTheConsolesLanguage() throws Exception {
        String url = "/api/v1/checks/" + UUID.randomUUID();
        String plain = mvc.perform(get(url).header(HEADER, KEY_1)).andExpect(status().isNotFound()).andReturn().getResponse()
                .getContentAsString();
        String withCookie = mvc.perform(get(url).header(HEADER, KEY_1).cookie(new Cookie(ConsoleLocaleResolver.COOKIE, "vi"))
                        .param("lang", "vi"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(withCookie.replaceAll("\"requestId\":\"[^\"]*\"", ""))
                .isEqualTo(plain.replaceAll("\"requestId\":\"[^\"]*\"", ""));
    }
}
