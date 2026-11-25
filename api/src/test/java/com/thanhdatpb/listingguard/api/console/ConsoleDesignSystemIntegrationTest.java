package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.brand.BrandAssets;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The visual identity of the console (ADR-040): the brand files (tokens, fonts, logos, icons and the base style sheet) are public and cached for a year, the sign-in pages use them with their
 * CSRF field intact, and every console page renders in the new shell (the sidebar, the skip link, the current page marked, the account)
 * while keeping the style sheet it had.
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
class ConsoleDesignSystemIntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    BrandAssets assets;

    @Autowired
    ConsoleAccountRepository accounts;

    @MockitoBean
    JavaMailSender mailSender;

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    void theVersionOfTheAssetsIsTwelveHexadecimalCharacters() {
        assertThat(assets.version()).matches("[0-9a-f]{12}");
    }

    @Test
    void theBrandFilesAndTheStyleSheetAreReadWithoutSigningInAndCachedForAYear() throws Exception {
        for (String path : new String[] {"/brand/tokens.css", "/brand/fonts/be-vietnam-pro-latin-400-normal.woff2",
                "/brand/fonts/be-vietnam-pro-vietnamese-700-normal.woff2", "/brand/logo/lockup-horizontal.svg",
                "/brand/logo/lockup-horizontal-reverse.svg", "/brand/logo/symbol.svg", "/brand/favicon.ico",
                "/brand/icons/icon-180.png", "/brand/app.css"}) {
            MvcResult result = mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn();
            String cache = result.getResponse().getHeader("Cache-Control");
            assertThat(cache).as(path).contains("max-age=31536000").contains("public").contains("immutable");
            assertThat(result.getResponse().getContentAsByteArray()).as(path + " has content").isNotEmpty();
        }
        assertThat(mvc.perform(get("/brand/tokens.css")).andReturn().getResponse().getContentType()).startsWith("text/css");
        assertThat(mvc.perform(get("/brand/logo/symbol.svg")).andReturn().getResponse().getContentType()).startsWith("image/svg+xml");
        assertThat(body(mvc.perform(get("/brand/tokens.css")).andReturn())).contains("--brand: #1f3fbf").contains("Be Vietnam Pro");
    }

    @Test
    void aBrandFileThatDoesNotExistIsA404NotASignInRedirect() throws Exception {
        mvc.perform(get("/brand/no-such-file.css")).andExpect(status().isNotFound());
    }

    @Test
    void theOtherConsoleFilesStayBehindTheSignIn() throws Exception {
        for (String path : new String[] {"/console/app.css", "/console/editor.css", "/console/bulk.css", "/console/vendor/htmx.min.js", "/console"}) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(header().string("Location", "/login"));
        }
    }

    @Test
    void theSignInPageUsesTheBrandAndKeepsItsFormAndItsCsrfField() throws Exception {
        String html = body(mvc.perform(get("/login")).andExpect(status().isOk()).andReturn());

        assertThat(html).contains("/brand/tokens.css?v=" + assets.version()).contains("/brand/app.css?v=" + assets.version())
                .contains("/brand/favicon.ico?v=").contains("rel=\"apple-touch-icon\"").contains("name=\"theme-color\"")
                .contains("class=\"lg-card lg-card--raised lg-auth__card\"").contains("<picture>")
                .contains("lockup-horizontal-reverse.svg").contains("prefers-color-scheme: dark")
                .contains("alt=\"Listing Guard\"").contains("<h1 class=\"lg-auth__title\">Sign in to Listing Guard</h1>")
                .contains("action=\"/login\"").contains("type=\"email\"").contains("name=\"_csrf\"")
                .doesNotContain("app-side").doesNotContain("style=\"");
    }

    @Test
    void aSignInThatCarriesTheTokenOfThePageIsNotRefusedForCsrf() throws Exception {
        MvcResult page = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        Matcher token = TOKEN.matcher(body(page));
        assertThat(token.find()).as("the hidden _csrf field").isTrue();
        MockHttpSession session = (MockHttpSession) page.getRequest().getSession(false);
        assertThat(session).isNotNull();

        MvcResult answer = mvc.perform(post("/login").session(session).param("_csrf", token.group(1))
                .param("email", "design-" + System.nanoTime() + "@example.test")).andReturn();

        assertThat(answer.getResponse().getStatus()).as("a valid token is not a 403").isNotEqualTo(403);
        assertThat(mvc.perform(post("/login").param("email", "x@example.test")).andReturn().getResponse().getStatus())
                .as("without the token it is refused").isEqualTo(403);
    }

    @Test
    void theConfirmationPageUsesTheSameCard() throws Exception {
        String html = body(mvc.perform(get("/login/verify").param("token", "not-a-token")).andExpect(status().isOk()).andReturn());

        assertThat(html).contains("/brand/app.css?v=" + assets.version()).contains("lg-auth__card").contains("role=\"alert\"")
                .doesNotContain("app-side");
    }

    @Test
    void everyConsolePageRendersInTheShellWithTheRightPageMarked() throws Exception {
        TestClient client = PlanTestSupport.newClient(provisioning, clients, PlanCode.FREE);
        ConsoleUser user = userOf(client);
        accounts.save(new ConsoleAccount(user.email(), user.clientId()));
        String clientName = clients.findById(client.id()).orElseThrow().getName();
        String[][] pages = {
                {"/console", "Dashboard"}, {"/console/check", "Check listing"}, {"/console/bulk", "Bulk check"},
                {"/console/check/edit", "Compare a listing edit"}, {"/console/history", "History"}, {"/console/reaudit", "Re-audit"}, {"/console/watchlist", "Watchlist"},
                {"/console/stats", "Statistics"}, {"/console/report", "Report"}, {"/console/api-keys", "API keys"},
                {"/console/policy-changes", "Policy changes"}, {"/console/pod-readiness", "POD readiness"}};

        for (String[] page : pages) {
            String html = body(mvc.perform(get(page[0]).with(signedIn(user))).andExpect(status().isOk()).andReturn());
            String as = page[0] + " (" + page[1] + ")";

            assertThat(html).as(as).contains("class=\"app-side\"").contains("href=\"#main\"").contains("id=\"main\"")
                    .contains("/brand/tokens.css?v=" + assets.version()).contains("/brand/app.css?v=" + assets.version())
                    .contains("rel=\"icon\"").contains("action=\"/logout\"").contains("app-account__avatar")
                    .contains(user.email()).contains(clientName).contains(">Check<", ">Monitor<", ">Manage<", ">Experimental<");
            assertThat(html.indexOf("/brand/tokens.css")).as(as + ": tokens before app.css").isLessThan(html.indexOf("/brand/app.css"));
            assertThat(countOf(html, "class=\"app-nav__link\"[^>]*aria-current=\"page\"")).as(as + ": one marked link").isEqualTo(1);
            assertThat(html).as(as).containsPattern("aria-current=\"page\"[^>]*>" + Pattern.quote(page[1]) + "<");
            assertThat(html).as(as).doesNotContain("href=\"/console/review-queue\"");
        }
    }

    @Test
    void theNavigationGroupsThePagesAsTheDesignDoes() throws Exception {
        ConsoleUser user = userOf(PlanTestSupport.newClient(provisioning, clients, PlanCode.FREE));
        String html = body(mvc.perform(get("/console").with(signedIn(user))).andReturn());

        int check = html.indexOf(">Check<");
        int monitor = html.indexOf(">Monitor<");
        int manage = html.indexOf(">Manage<");
        int experimental = html.indexOf(">Experimental<");
        assertThat(check).isPositive();
        assertThat(monitor).isGreaterThan(check);
        assertThat(manage).isGreaterThan(monitor);
        assertThat(experimental).isGreaterThan(manage);
        for (String label : new String[] {">Check listing<", ">Bulk check<", ">Compare a listing edit<"}) {
            assertThat(html.indexOf(label)).as(label).isBetween(check, monitor);
        }
        for (String label : new String[] {">History<", ">Re-audit<"}) {
            assertThat(html.indexOf(label)).as(label).isBetween(monitor, manage);
        }
        for (String label : new String[] {">Watchlist<", ">Statistics<", ">Report<", ">API keys<"}) {
            assertThat(html.indexOf(label)).as(label).isBetween(manage, experimental);
        }
        for (String label : new String[] {">Policy changes<", ">POD readiness<"}) {
            assertThat(html.indexOf(label)).as(label).isGreaterThan(experimental);
        }
        assertThat(html.indexOf(">Sign out<")).isGreaterThan(html.indexOf(">API keys<"));
    }

    @Test
    void everyPageLoadsOnlyTheSheetsItNeedsAndSetsNoInlineColor() throws Exception {
        ConsoleUser user = userOf(PlanTestSupport.newClient(provisioning, clients, PlanCode.FREE));
        String editor = body(mvc.perform(get("/console/check").with(signedIn(user))).andReturn());
        String keys = body(mvc.perform(get("/console/api-keys").with(signedIn(user))).andReturn());

        assertThat(editor).contains("/console/editor.css").contains("/console/editor.js");
        assertThat(keys).contains("/brand/app.css").doesNotContain("/console/console.css").doesNotContain("/console/editor.css");
        assertThat(editor).doesNotContain("/console/console.css").doesNotContain("/console/bulk.css");
        assertThat(editor.indexOf("/brand/app.css")).isLessThan(editor.indexOf("/console/editor.css"));
        assertThat(editor).doesNotContain("style=\"");
    }

    private static int countOf(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
