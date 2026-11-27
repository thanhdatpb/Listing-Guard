package com.thanhdatpb.listingguard.api.workspace.audit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * The configuration of the workspace's background audit (ADR-061). Nothing here exists while {@code listing-guard.workspace.audit.enabled} is
 * false, or while the workspace or the Console is off: no queue, no worker, no page. The settings bind only when the audit is on.
 */
@Configuration
@AuditEnabled
@EnableConfigurationProperties({AuditProperties.class, AlertProperties.class})
public class WorkspaceAuditConfig {

    /**
     * Refuses to start when e-mail alerts are on and cannot be sent: outside the {@code dev} profile they need the SMTP server of the Console
     * and its sender address, like the sign-in link. The {@code dev} profile logs the e-mail instead.
     */
    @Bean
    public AuditStartupCheck workspaceAuditStartupCheck(AlertProperties alerts, Environment environment) {
        if (alerts.enabled() && !environment.acceptsProfiles(Profiles.of("dev"))) {
            String host = environment.getProperty("spring.mail.host", "");
            String from = environment.getProperty("listing-guard.console.mail-from", "");
            if (host.isBlank() || from.isBlank()) {
                throw new IllegalStateException("listing-guard.workspace.alerts.email.enabled needs spring.mail.host and "
                        + "listing-guard.console.mail-from (the SMTP server of the sign-in link), or the dev profile, which logs the e-mail.");
            }
        }
        return new AuditStartupCheck();
    }

    /** Marker bean: its creation means the check passed. */
    public static final class AuditStartupCheck {
    }
}
