package com.thanhdatpb.listingguard.api.console.bulk;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleMessagesTestSupport;
import java.util.Locale;

/**
 * The console's messages in one fixed language for a unit test of the bulk check (ADR-028), without a Spring context and without
 * touching the language of the thread: the same bundles as {@code application.yml}, asked for a locale given here, never the one of
 * the machine.
 */
final class BulkTestMessages {

    private BulkTestMessages() {
    }

    static ConsoleMessages vi() {
        return in(Locale.forLanguageTag("vi"));
    }

    static ConsoleMessages en() {
        return in(Locale.ENGLISH);
    }

    private static ConsoleMessages in(Locale locale) {
        return new ConsoleMessages(ConsoleMessagesTestSupport.source()) {
            @Override
            public String get(String code, Object... args) {
                return get(locale, code, args);
            }

            @Override
            public String ruleExplanation(String ruleId) {
                return ruleExplanation(locale, ruleId);
            }
        };
    }
}
