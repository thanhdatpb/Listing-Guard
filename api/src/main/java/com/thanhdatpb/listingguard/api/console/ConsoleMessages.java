package com.thanhdatpb.listingguard.api.console;

import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * The console's way to a message (ADR-028): the sentences its Java builds for a page, and the two lookups a template cannot do
 * with {@code #{...}} alone. It is the bean {@code consoleMessages}, so a template reaches it as {@code ${@consoleMessages...}}.
 *
 * <p>Every argument is turned into text before the lookup, so a number is never formatted with the grouping of the page's
 * language ("1,000" against "1.000"). A missing key is an exception, not text on a page: a test holds that every key in use
 * exists, so it does not happen in a running console.
 */
@Component("consoleMessages")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleMessages {

    private static final String RULE_PREFIX = "rule.";

    private final MessageSource source;

    public ConsoleMessages(MessageSource source) {
        this.source = source;
    }

    /** The message in the language of the current request. */
    public String get(String code, Object... args) {
        return get(LocaleContextHolder.getLocale(), code, args);
    }

    public String get(Locale locale, String code, Object... args) {
        Object[] text = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            text[i] = String.valueOf(args[i]);
        }
        return source.getMessage(code, text.length == 0 ? null : text, locale);
    }

    /** What the rule checks, in the language of the page; {@code null} when no language has a sentence for it. */
    public String ruleExplanation(String ruleId) {
        return ruleExplanation(LocaleContextHolder.getLocale(), ruleId);
    }

    public String ruleExplanation(Locale locale, String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            return null;
        }
        return source.getMessage(RULE_PREFIX + ruleId, null, null, locale);
    }

    /**
     * The statement of this language about the trademark result, to print above the ruleset's own disclaimer. {@code null} when it
     * is the same text as the disclaimer, so a page never says one thing twice. The disclaimer itself is never changed or
     * replaced by this.
     */
    public String disclaimerNote(String disclaimer) {
        String note = get("trademark.disclaimer.note");
        return disclaimer != null && disclaimer.strip().equals(note.strip()) ? null : note;
    }
}
