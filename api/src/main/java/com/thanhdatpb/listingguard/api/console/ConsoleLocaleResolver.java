package com.thanhdatpb.listingguard.api.console;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Picks the language of a console page (ADR-028): a {@code lang} parameter of this request, else the {@code lg_lang} cookie, else
 * the configured default. It is the application's {@code localeResolver}, but it only decides for the console's own paths
 * ({@code /console/**}, {@code /login/**}); every other request goes to the {@link AcceptHeaderLocaleResolver} that the
 * application used before, so the problem details and validation messages of the API do not change language.
 *
 * <p>Only a supported language code counts, anywhere it appears. {@code Accept-Language} is not read for a console page: the
 * operator decides the default. The parameter is read on a {@code GET} only, and {@link #remember} stores it in a cookie that
 * holds the code and nothing else.
 */
public final class ConsoleLocaleResolver implements LocaleResolver {

    public static final String COOKIE = "lg_lang";
    public static final String PARAMETER = "lang";

    private static final Duration LIFETIME = Duration.ofDays(365);

    private final ConsoleLocales locales;
    private final boolean cookieSecure;
    private final LocaleResolver others = new AcceptHeaderLocaleResolver();

    public ConsoleLocaleResolver(ConsoleLocales locales, boolean cookieSecure) {
        this.locales = locales;
        this.cookieSecure = cookieSecure;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        if (!isConsolePath(request)) {
            return others.resolveLocale(request);
        }
        return requested(request).or(() -> fromCookie(request)).orElse(locales.defaultLocale());
    }

    /** Stores the language of this request's {@code lang} parameter, when it has a supported one. */
    public void remember(HttpServletRequest request, HttpServletResponse response) {
        requested(request).ifPresent(locale -> setLocale(request, response, locale));
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        if (response == null) {
            return;
        }
        if (locale == null) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
            return;
        }
        if (ConsoleLocales.isSupported(locale.getLanguage())) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(locale.getLanguage(), LIFETIME).toString());
        }
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value).path("/").maxAge(maxAge).httpOnly(true).secure(cookieSecure)
                .sameSite("Lax").build();
    }

    private static Optional<Locale> requested(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return Optional.empty();
        }
        return ConsoleLocales.parse(request.getParameter(PARAMETER));
    }

    private static Optional<Locale> fromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) {
                return ConsoleLocales.parse(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** The pages of the console and of its sign-in, as the security chain of the console names them. */
    static boolean isConsolePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (path == null) {
            return false;
        }
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path.equals("/console") || path.startsWith("/console/") || path.equals("/login") || path.startsWith("/login/");
    }
}
