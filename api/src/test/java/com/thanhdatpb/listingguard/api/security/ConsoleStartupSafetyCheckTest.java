package com.thanhdatpb.listingguard.api.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.console.ConsoleProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ConsoleStartupSafetyCheckTest {

    @Test
    void productionConsoleStartupFailsWhenSmtpIsMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        ConsoleProperties properties = new ConsoleProperties(true, "https://console.example.test", true,
                "noreply@example.test", 5, 30, Duration.ofMinutes(15), 5);

        assertThatThrownBy(() -> new ConsoleStartupSafetyCheck(properties, null, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP is not configured");
    }

    @Test
    void theKeyLimitMustBeAtLeastOneAndTheDefaultIsFive() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        for (int bad : new int[] {0, -1}) {
            ConsoleProperties properties = new ConsoleProperties(true, "http://localhost:8080", false,
                    "", 5, 30, Duration.ofMinutes(15), bad);
            assertThatThrownBy(() -> new ConsoleStartupSafetyCheck(properties, null, environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max-active-keys must be at least 1");
        }
        ConsoleProperties one = new ConsoleProperties(true, "http://localhost:8080", false,
                "", 5, 30, Duration.ofMinutes(15), 1);
        new ConsoleStartupSafetyCheck(one, null, environment);
    }

    @Test
    void onlyDevMayUseAnInsecureCookieAndWithoutSmtp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        ConsoleProperties properties = new ConsoleProperties(true, "http://localhost:8080", false,
                "", 5, 30, Duration.ofMinutes(15), 5);

        new ConsoleStartupSafetyCheck(properties, null, environment);
    }

    @Test
    void cloudflareAccessDoesNotRequireSmtpAndRequiresTeamAndAudience() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        ConsoleProperties configured = new ConsoleProperties(true, "https://console.example.test", true,
                "", 5, 30, Duration.ofMinutes(15), 5, com.thanhdatpb.listingguard.api.console.ConsoleAuthMode.CLOUDFLARE_ACCESS,
                "https://team.cloudflareaccess.com", "aud", "", Duration.ofHours(1), "example.test", "",
                false, "");
        new ConsoleStartupSafetyCheck(configured, null, environment);

        ConsoleProperties noTeam = new ConsoleProperties(true, "https://console.example.test", true,
                "", 5, 30, Duration.ofMinutes(15), 5, com.thanhdatpb.listingguard.api.console.ConsoleAuthMode.CLOUDFLARE_ACCESS,
                "", "aud", "", Duration.ofHours(1), "example.test", "", false, "");
        assertThatThrownBy(() -> new ConsoleStartupSafetyCheck(noTeam, null, environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ACCESS_TEAM_DOMAIN")
                .hasMessageContaining("ACCESS_AUD");

        ConsoleProperties noAudience = new ConsoleProperties(true, "https://console.example.test", true,
                "", 5, 30, Duration.ofMinutes(15), 5, com.thanhdatpb.listingguard.api.console.ConsoleAuthMode.CLOUDFLARE_ACCESS,
                "https://team.cloudflareaccess.com", "", "", Duration.ofHours(1), "example.test", "", false, "");
        assertThatThrownBy(() -> new ConsoleStartupSafetyCheck(noAudience, null, environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ACCESS_TEAM_DOMAIN")
                .hasMessageContaining("ACCESS_AUD");
    }
}
