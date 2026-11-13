package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Which language a console page gets, and the cookie and links that change it (ADR-028). No Spring context: the resolver, the
 * language list and the switch links are plain objects.
 */
class ConsoleLocaleResolverTest {

    private static final Locale ENGLISH = Locale.forLanguageTag("en");
    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");

    private static ConsoleLocales locales(String defaultLocale) {
        return new ConsoleLocales(new ConsoleProperties(true, "https://console.example.test", true, "noreply@example.test", 5, 30,
                Duration.ofMinutes(15), 5, defaultLocale));
    }

    private static ConsoleLocaleResolver resolver(String defaultLocale, boolean secure) {
        return new ConsoleLocaleResolver(locales(defaultLocale), secure);
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    @Test
    void withNothingChosenThePageGetsTheConfiguredDefault() {
        assertThat(resolver("en", true).resolveLocale(request("GET", "/console"))).isEqualTo(ENGLISH);
        assertThat(resolver("vi", true).resolveLocale(request("GET", "/console"))).isEqualTo(VIETNAMESE);
        assertThat(resolver("vi", true).resolveLocale(request("GET", "/login"))).isEqualTo(VIETNAMESE);
    }

    @Test
    void theCookieBeatsTheDefaultAndTheParameterBeatsTheCookie() {
        ConsoleLocaleResolver resolver = resolver("en", true);

        MockHttpServletRequest withCookie = request("GET", "/console/check");
        withCookie.setCookies(new Cookie(ConsoleLocaleResolver.COOKIE, "vi"));
        assertThat(resolver.resolveLocale(withCookie)).isEqualTo(VIETNAMESE);

        withCookie.setParameter("lang", "en");
        assertThat(resolver.resolveLocale(withCookie)).isEqualTo(ENGLISH);
    }

    @Test
    void aLanguageThatTheConsoleDoesNotHaveIsIgnoredWhereverItComesFrom() {
        ConsoleLocaleResolver resolver = resolver("vi", true);

        for (String bad : new String[] {"fr", "", " ", "en-US", "english", "../en", "en;x", "en\r\nSet-Cookie: x=y"}) {
            MockHttpServletRequest parameter = request("GET", "/console");
            parameter.setParameter("lang", bad);
            MockHttpServletRequest cookie = request("GET", "/console");
            cookie.setCookies(new Cookie(ConsoleLocaleResolver.COOKIE, bad));

            assertThat(resolver.resolveLocale(parameter)).as("parameter '" + bad + "'").isEqualTo(VIETNAMESE);
            assertThat(resolver.resolveLocale(cookie)).as("cookie '" + bad + "'").isEqualTo(VIETNAMESE);
        }
    }

    @Test
    void theCodeIsMatchedIgnoringCase() {
        MockHttpServletRequest request = request("GET", "/console");
        request.setParameter("lang", "EN");

        assertThat(resolver("vi", true).resolveLocale(request)).isEqualTo(ENGLISH);
    }

    @Test
    void theParameterIsReadOnAGetOnly() {
        MockHttpServletRequest post = request("POST", "/console/check");
        post.setParameter("lang", "en");

        assertThat(resolver("vi", true).resolveLocale(post)).isEqualTo(VIETNAMESE);
    }

    @Test
    void theBrowsersAcceptLanguageDoesNotDecideAConsolePage() {
        MockHttpServletRequest request = request("GET", "/console");
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "vi");

        assertThat(resolver("en", true).resolveLocale(request)).isEqualTo(ENGLISH);
    }

    @Test
    void aRequestOutsideTheConsoleKeepsItsOwnLanguageAndNeverReadsTheCookieOrTheParameter() {
        ConsoleLocaleResolver resolver = resolver("vi", true);
        for (String path : new String[] {"/api/v1/checks", "/health", "/consolex", "/loginx"}) {
            MockHttpServletRequest request = request("GET", path);
            request.addPreferredLocale(Locale.GERMAN);
            request.setCookies(new Cookie(ConsoleLocaleResolver.COOKIE, "vi"));
            request.setParameter("lang", "vi");

            assertThat(resolver.resolveLocale(request)).as(path).isEqualTo(Locale.GERMAN);
        }
    }

    @Test
    void theConsolePathsAreTheOnesTheSecurityChainOfTheConsoleNames() {
        for (String path : new String[] {"/console", "/console/", "/console/check", "/login", "/login/verify"}) {
            assertThat(ConsoleLocaleResolver.isConsolePath(request("GET", path))).as(path).isTrue();
        }
        for (String path : new String[] {"/", "/api/v1/checks", "/consolex", "/loginx", "/logout"}) {
            assertThat(ConsoleLocaleResolver.isConsolePath(request("GET", path))).as(path).isFalse();
        }
        MockHttpServletRequest inContext = request("GET", "/app/console/check");
        inContext.setContextPath("/app");
        assertThat(ConsoleLocaleResolver.isConsolePath(inContext)).isTrue();
    }

    @Test
    void rememberingWritesOneCookieThatHoldsTheCodeAndNothingElse() {
        MockHttpServletRequest request = request("GET", "/console");
        request.setParameter("lang", "vi");
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver("en", true).remember(request, response);

        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(1);
        assertThat(cookies.get(0)).startsWith("lg_lang=vi;").contains("Path=/").contains("Max-Age=31536000").contains("HttpOnly")
                .contains("SameSite=Lax").contains("Secure");
    }

    @Test
    void theCookieIsSecureOnlyWhenTheConsoleCookiesAre() {
        MockHttpServletRequest request = request("GET", "/console");
        request.setParameter("lang", "en");
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver("vi", false).remember(request, response);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith("lg_lang=en;").doesNotContain("Secure");
    }

    @Test
    void nothingIsRememberedWithoutAUsableParameterOrOnAPost() {
        ConsoleLocaleResolver resolver = resolver("en", true);

        MockHttpServletResponse none = new MockHttpServletResponse();
        resolver.remember(request("GET", "/console"), none);
        MockHttpServletRequest unsupported = request("GET", "/console");
        unsupported.setParameter("lang", "fr");
        resolver.remember(unsupported, none);
        MockHttpServletRequest post = request("POST", "/console/check");
        post.setParameter("lang", "vi");
        resolver.remember(post, none);

        assertThat(none.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void settingNullLanguageClearsTheCookieAndAnUnsupportedOneIsNotStored() {
        ConsoleLocaleResolver resolver = resolver("en", true);

        MockHttpServletResponse cleared = new MockHttpServletResponse();
        resolver.setLocale(request("GET", "/console"), cleared, null);
        MockHttpServletResponse unsupported = new MockHttpServletResponse();
        resolver.setLocale(request("GET", "/console"), unsupported, Locale.FRENCH);

        assertThat(cleared.getHeader(HttpHeaders.SET_COOKIE)).startsWith("lg_lang=;").contains("Max-Age=0");
        assertThat(unsupported.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void theDefaultMustBeALanguageTheConsoleHas() {
        assertThat(locales("vi").defaultLocale()).isEqualTo(VIETNAMESE);
        assertThat(locales(" EN ").defaultLocale()).isEqualTo(ENGLISH);
        assertThatThrownBy(() -> locales("fr")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-locale must be one of en, vi");
        assertThatThrownBy(() -> locales("")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everySupportedLanguageHasItsBundlesAndNamesItselfInItsOwnWords() {
        for (ConsoleLocales.Language language : ConsoleLocales.SUPPORTED) {
            assertThat(language.name()).isNotBlank();
            assertThat(ConsoleMessagesTestSupport.messages().get(Locale.forLanguageTag(language.code()), "lang.aria")).isNotBlank();
        }
        assertThat(ConsoleLocales.SUPPORTED.get(0).code()).as("the first language is the base bundle's").isEqualTo("en");
        assertThat(ConsoleLocales.SUPPORTED.stream().map(ConsoleLocales.Language::name)).contains("English", "Tiếng Việt");
    }

    @Test
    void theSwitchLinksKeepTheQueryOfAGetAndReplaceTheLanguage() {
        MockHttpServletRequest page = request("GET", "/console/check");
        page.setQueryString("marketplace=AMAZON&lang=vi&x=1");

        assertThat(ConsoleLanguageAdvice.href(page, "en")).isEqualTo("/console/check?marketplace=AMAZON&x=1&lang=en");
        assertThat(ConsoleLanguageAdvice.href(request("GET", "/login"), "vi")).isEqualTo("/login?lang=vi");
    }

    @Test
    void theSwitchLinksOfAPostPointToThePageThatHasTheFormAndDropTheQuery() {
        MockHttpServletRequest check = request("POST", "/console/check");
        check.setQueryString("lang=vi&secret=1");
        MockHttpServletRequest apply = request("POST", "/console/check/apply");
        MockHttpServletRequest keys = request("POST", "/console/api-keys");

        assertThat(ConsoleLanguageAdvice.href(check, "en")).isEqualTo("/console/check?lang=en");
        assertThat(ConsoleLanguageAdvice.href(apply, "en")).isEqualTo("/console/check?lang=en");
        assertThat(ConsoleLanguageAdvice.href(keys, "vi")).isEqualTo("/console/api-keys?lang=vi");
    }
}
