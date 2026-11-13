package com.thanhdatpb.listingguard.api.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * The links of the language switch that every console page shows in its corner (ADR-028): one per supported language, to this
 * same page with {@code lang} set. A link is a {@code GET}, so a page that was the answer to a {@code POST} points to the page
 * that has a form for it (the editor's "Apply suggestion" goes back to the editor) and drops its query.
 */
@ControllerAdvice(basePackages = "com.thanhdatpb.listingguard.api.console")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleLanguageAdvice {

    /** {@code name} is the language's own name; {@code href} is relative to the server. */
    public record LanguageOption(String code, String name, String href, boolean current) {
    }

    private static final String APPLY_SUFFIX = "/apply";

    @ModelAttribute("languageOptions")
    public List<LanguageOption> languageOptions(HttpServletRequest request) {
        Locale current = RequestContextUtils.getLocale(request);
        return ConsoleLocales.SUPPORTED.stream()
                .map(language -> new LanguageOption(language.code(), language.name(), href(request, language.code()),
                        language.code().equals(current.getLanguage())))
                .toList();
    }

    static String href(HttpServletRequest request, String code) {
        boolean get = "GET".equals(request.getMethod());
        String path = request.getRequestURI();
        if (!get && path.endsWith("/console/check" + APPLY_SUFFIX)) {
            path = path.substring(0, path.length() - APPLY_SUFFIX.length());
        }
        StringBuilder query = new StringBuilder();
        String raw = request.getQueryString();
        if (get && raw != null) {
            for (String part : raw.split("&")) {
                if (!part.isEmpty() && !part.equals(ConsoleLocaleResolver.PARAMETER)
                        && !part.startsWith(ConsoleLocaleResolver.PARAMETER + "=")) {
                    query.append(part).append('&');
                }
            }
        }
        return path + "?" + query + ConsoleLocaleResolver.PARAMETER + "=" + code;
    }
}
