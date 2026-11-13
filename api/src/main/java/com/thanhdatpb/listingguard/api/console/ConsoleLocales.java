package com.thanhdatpb.listingguard.api.console;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The languages of the console (ADR-028) and the one a page uses when its reader has not chosen. The default comes from
 * {@code listing-guard.console.default-locale}; a value that is not a supported language stops the start-up
 * ({@code ConsoleStartupSafetyCheck}), so this class never has to fall back.
 *
 * <p>A language is added by a {@code messages_<code>.properties}, a {@code rules_<code>.properties} and one entry of
 * {@link #SUPPORTED}.
 */
@Component
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleLocales {

    /**
     * A language the console offers. {@code name} is written in that language on purpose and is not a message: a reader on a
     * page in the wrong language must still find their own.
     */
    public record Language(String code, String name) {
    }

    /** The first language is the one of the base bundle ({@code messages.properties}). */
    public static final List<Language> SUPPORTED = List.of(new Language("en", "English"), new Language("vi", "Tiếng Việt"));

    private final Locale defaultLocale;

    public ConsoleLocales(ConsoleProperties properties) {
        this.defaultLocale = parse(properties.defaultLocale()).orElseThrow(() -> new IllegalStateException(
                "listing-guard.console.default-locale must be one of " + codes() + "."));
    }

    public Locale defaultLocale() {
        return defaultLocale;
    }

    /** The locale of a supported language code (exact, ignoring case and blanks around it); anything else is empty. */
    public static Optional<Locale> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String wanted = code.strip();
        return SUPPORTED.stream().filter(language -> language.code().equalsIgnoreCase(wanted)).findFirst()
                .map(language -> Locale.forLanguageTag(language.code()));
    }

    public static boolean isSupported(String code) {
        return parse(code).isPresent();
    }

    public static String codes() {
        return String.join(", ", SUPPORTED.stream().map(Language::code).toList());
    }
}
