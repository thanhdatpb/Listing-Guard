package com.thanhdatpb.listingguard.api.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.console.ConsoleProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ConsoleStartupSafetyCheckTest {

    @Test
    void productionConsoleRequiresSmtpConfiguration() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        ConsoleProperties properties = new ConsoleProperties(true, "https://console.example.test", true,
                "noreply@example.test", 5, 30, Duration.ofMinutes(15));

        assertThatThrownBy(() -> new ConsoleStartupSafetyCheck(properties, null, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP is not configured");
    }

    @Test
    void onlyDevMayUseAnInsecureCookieAndWithoutSmtp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        ConsoleProperties properties = new ConsoleProperties(true, "http://localhost:8080", false,
                "", 5, 30, Duration.ofMinutes(15));

        new ConsoleStartupSafetyCheck(properties, null, environment);
    }
}
