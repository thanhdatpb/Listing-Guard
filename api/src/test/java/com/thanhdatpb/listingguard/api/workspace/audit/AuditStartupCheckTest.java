package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The audit refuses to start with e-mail alerts it cannot send, and its settings refuse nonsense (ADR-061). No database. */
class AuditStartupCheckTest {

    private final WorkspaceAuditConfig config = new WorkspaceAuditConfig();

    @Test
    void alertsOutsideTheDevProfileNeedTheSmtpServerAndTheSenderOfTheSignInLink() {
        AlertProperties on = new AlertProperties(true, 3, 40, 40);

        assertThatThrownBy(() -> config.workspaceAuditStartupCheck(on, new MockEnvironment())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.host");
        assertThatThrownBy(() -> config.workspaceAuditStartupCheck(on, new MockEnvironment().withProperty("spring.mail.host", "smtp.example.test")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mail-from");
        assertThat(config.workspaceAuditStartupCheck(on, new MockEnvironment().withProperty("spring.mail.host", "smtp.example.test")
                .withProperty("listing-guard.console.mail-from", "noreply@example.test"))).isNotNull();
    }

    @Test
    void theDevProfileLogsTheEmailInsteadSoItNeedsNoServer() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");

        assertThat(config.workspaceAuditStartupCheck(new AlertProperties(true, 3, 40, 40), dev)).isNotNull();
    }

    @Test
    void alertsThatAreOffNeedNothing() {
        assertThat(config.workspaceAuditStartupCheck(new AlertProperties(false, 3, 40, 40), new MockEnvironment())).isNotNull();
    }

    @Test
    void theSettingsRefuseNonsenseAndTheCronMustParse() {
        assertThatThrownBy(() -> new AuditProperties(true, true, 0, java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(2),
                java.time.Duration.ofSeconds(30), "", 30, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditProperties(true, true, 9, java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(2),
                java.time.Duration.ofSeconds(30), "", 30, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditProperties(true, true, 2, java.time.Duration.ZERO, java.time.Duration.ofMinutes(2),
                java.time.Duration.ofSeconds(30), "", 30, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditProperties(true, true, 2, java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(2),
                java.time.Duration.ofSeconds(30), "not a cron", 30, 100)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schedule-cron");
        AuditProperties ok = new AuditProperties(true, true, 2, java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(2),
                java.time.Duration.ofSeconds(30), "0 0 3 * * *", 30, 100);
        assertThat(ok.scheduled()).isTrue();
        assertThat(new AuditProperties(false, true, 2, java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(2),
                java.time.Duration.ofSeconds(30), "", 30, 100).scheduled()).isFalse();
        assertThatThrownBy(() -> new AlertProperties(true, 0, 40, 40)).isInstanceOf(IllegalArgumentException.class);
    }
}
