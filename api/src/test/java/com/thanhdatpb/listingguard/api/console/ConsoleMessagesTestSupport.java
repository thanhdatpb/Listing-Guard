package com.thanhdatpb.listingguard.api.console;

import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The console's messages for a unit test (ADR-028): the same bundles and the same settings as {@code application.yml}
 * ({@code basename: messages,rules}, UTF-8, no fallback to the language of the JVM), without a Spring context. A test that
 * reads a sentence sets the language it expects on {@code LocaleContextHolder} first, and puts it back after: the JVM's own
 * language is whatever the machine says, and must not decide what a test sees.
 */
public final class ConsoleMessagesTestSupport {

    private ConsoleMessagesTestSupport() {
    }

    public static ResourceBundleMessageSource source() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("messages", "rules");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    public static ConsoleMessages messages() {
        return new ConsoleMessages(source());
    }
}
