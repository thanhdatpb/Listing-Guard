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
        @DefaultValue("MAGIC_LINK") ConsoleAuthMode authMode,
        @DefaultValue("") String accessTeamDomain,
        @DefaultValue("") String accessAud,
        @DefaultValue("") String accessCertsUrl,
        @DefaultValue("1h") Duration accessCertCacheTtl,
        @DefaultValue("") String accessAllowedDomains,
        @DefaultValue("") String accessAllowedEmails,
        @DefaultValue("false") boolean accessAutoCreate,
        @DefaultValue("") String accessDefaultClientName,
        // The language of a console page whose reader has not chosen one (ADR-028): a code of ConsoleLocales.SUPPORTED.
        @DefaultValue("en") String defaultLocale) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ConsoleProperties { }

    /** The settings that existed before the Cloudflare Access sign-in and the language (ADR-027, ADR-028), all at their defaults. */
    public ConsoleProperties(boolean enabled, String baseUrl, boolean cookieSecure, String mailFrom,
                             int emailLimit, int ipLimit, Duration rateWindow, int maxActiveKeys) {
        this(enabled, baseUrl, cookieSecure, mailFrom, emailLimit, ipLimit, rateWindow, maxActiveKeys, "en");
    }

    /** The same, with the language of a page whose reader has not chosen one. */
    public ConsoleProperties(boolean enabled, String baseUrl, boolean cookieSecure, String mailFrom,
                             int emailLimit, int ipLimit, Duration rateWindow, int maxActiveKeys, String defaultLocale) {
        this(enabled, baseUrl, cookieSecure, mailFrom, emailLimit, ipLimit, rateWindow, maxActiveKeys,
                ConsoleAuthMode.MAGIC_LINK, "", "", "",
                Duration.ofHours(1), "", "", false, "", defaultLocale);
    }

    /** Every setting of ADR-027 with the default language. */
    public ConsoleProperties(boolean enabled, String baseUrl, boolean cookieSecure, String mailFrom,
                             int emailLimit, int ipLimit, Duration rateWindow, int maxActiveKeys, ConsoleAuthMode authMode,
                             String accessTeamDomain, String accessAud, String accessCertsUrl, Duration accessCertCacheTtl,
                             String accessAllowedDomains, String accessAllowedEmails, boolean accessAutoCreate,
                             String accessDefaultClientName) {
        this(enabled, baseUrl, cookieSecure, mailFrom, emailLimit, ipLimit, rateWindow, maxActiveKeys, authMode,
                accessTeamDomain, accessAud, accessCertsUrl, accessCertCacheTtl, accessAllowedDomains, accessAllowedEmails,
                accessAutoCreate, accessDefaultClientName, "en");
    }

    public boolean magicLinkEnabled() { return authMode.usesMagicLink(); }
    public boolean cloudflareAccessEnabled() { return authMode.usesCloudflareAccess(); }
    public String resolvedAccessCertsUrl() {
        return accessCertsUrl.isBlank() ? accessTeamDomain.replaceAll("/+$", "") + "/cdn-cgi/access/certs" : accessCertsUrl;
    }

    public java.util.Set<String> allowedDomains() {
        return java.util.Arrays.stream(accessAllowedDomains.split(","))
                .map(String::strip).map(value -> value.startsWith("@") ? value.substring(1) : value)
                .filter(value -> !value.isBlank()).map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public java.util.Set<String> allowedEmails() {
        return java.util.Arrays.stream(accessAllowedEmails.split(","))
                .map(String::strip).filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
