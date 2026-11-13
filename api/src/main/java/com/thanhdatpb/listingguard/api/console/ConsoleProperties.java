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
        @DefaultValue("") String accessDefaultClientName) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ConsoleProperties { }

    public ConsoleProperties(boolean enabled, String baseUrl, boolean cookieSecure, String mailFrom,
                             int emailLimit, int ipLimit, Duration rateWindow, int maxActiveKeys) {
        this(enabled, baseUrl, cookieSecure, mailFrom, emailLimit, ipLimit, rateWindow, maxActiveKeys,
                ConsoleAuthMode.MAGIC_LINK, "", "", "",
                Duration.ofHours(1), "", "", false, "");
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
