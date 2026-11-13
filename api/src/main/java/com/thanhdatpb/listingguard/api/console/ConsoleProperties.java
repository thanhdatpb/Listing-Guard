package com.thanhdatpb.listingguard.api.console;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "listing-guard.console")
public record ConsoleProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("") String mailFrom,
        @DefaultValue("5") int emailLimit,
        @DefaultValue("30") int ipLimit,
        @DefaultValue("15m") Duration rateWindow,
        @DefaultValue("5") int maxActiveKeys,
        // The language of a console page whose reader has not chosen one (ADR-028): a code of ConsoleLocales.SUPPORTED.
        @DefaultValue("en") String defaultLocale) {
}
