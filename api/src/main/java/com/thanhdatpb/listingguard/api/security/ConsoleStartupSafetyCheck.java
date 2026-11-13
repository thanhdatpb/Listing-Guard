package com.thanhdatpb.listingguard.api.security;

import com.thanhdatpb.listingguard.api.console.ConsoleProperties;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

public final class ConsoleStartupSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(ConsoleStartupSafetyCheck.class);

    public ConsoleStartupSafetyCheck(ConsoleProperties properties, String smtpHost, Environment environment) {
        if (!properties.enabled()) return;
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        boolean dev = profiles.contains(StartupSafetyCheck.DEV_PROFILE);
        boolean prod = profiles.contains(StartupSafetyCheck.PROD_PROFILE);
        if (dev && prod) throw new IllegalStateException("The console cannot use both the 'dev' and 'prod' profiles.");

        URI baseUrl;
        try { baseUrl = URI.create(properties.baseUrl()); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("listing-guard.console.base-url must be an absolute http(s) URL.", ex); }
        if (!baseUrl.isAbsolute() || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || !("http".equalsIgnoreCase(baseUrl.getScheme()) || "https".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new IllegalStateException("listing-guard.console.base-url must be an absolute http(s) URL without user info, query or fragment.");
        }
        if (prod && (!"https".equalsIgnoreCase(baseUrl.getScheme()) || !properties.cookieSecure())) {
            throw new IllegalStateException("In production, listing-guard.console.base-url must use https and cookie-secure must be true.");
        }
        if (properties.magicLinkEnabled() && !dev && (smtpHost == null || smtpHost.isBlank() || properties.mailFrom().isBlank())) {
            throw new IllegalStateException("Console is enabled in production but SMTP is not configured for magic-link sign-in; set SPRING_MAIL_HOST and LISTING_GUARD_CONSOLE_MAIL_FROM.");
        }
        if (properties.cloudflareAccessEnabled()) {
            if (properties.accessTeamDomain().isBlank() || properties.accessAud().isBlank()) {
                throw new IllegalStateException("Cloudflare Access sign-in requires LISTING_GUARD_CONSOLE_ACCESS_TEAM_DOMAIN and LISTING_GUARD_CONSOLE_ACCESS_AUD.");
            }
            try {
                URI team = URI.create(properties.accessTeamDomain());
                URI certs = URI.create(properties.resolvedAccessCertsUrl());
                if (!"https".equalsIgnoreCase(team.getScheme()) || team.getHost() == null
                        || !"https".equalsIgnoreCase(certs.getScheme()) || certs.getHost() == null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Cloudflare Access team domain and certs URL must be absolute HTTPS URLs.", ex);
            }
            if (properties.allowedDomains().isEmpty() && properties.allowedEmails().isEmpty()) {
                throw new IllegalStateException("Cloudflare Access sign-in requires an email or domain allowlist.");
            }
            if (properties.accessAutoCreate() && properties.accessDefaultClientName().isBlank()) {
                throw new IllegalStateException("LISTING_GUARD_CONSOLE_ACCESS_DEFAULT_CLIENT_NAME is required when account auto-creation is enabled.");
            }
        }
        if (!properties.cookieSecure() && !dev) {
            throw new IllegalStateException("listing-guard.console.cookie-secure=false is only allowed with the 'dev' profile.");
        }
        if (properties.emailLimit() < 1 || properties.ipLimit() < 1 || properties.rateWindow().isZero()
                || properties.rateWindow().isNegative() || properties.rateWindow().toSeconds() < 1) {
            throw new IllegalStateException("Console login rate limits must be positive.");
        }
        if (properties.maxActiveKeys() < 1) throw new IllegalStateException("listing-guard.console.max-active-keys must be at least 1.");
        if (dev && properties.magicLinkEnabled()) log.warn("Console development mail delivery logs magic links. Do not use the 'dev' profile in production.");
    }
}
