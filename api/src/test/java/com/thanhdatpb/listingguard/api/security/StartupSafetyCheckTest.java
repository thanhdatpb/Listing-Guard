package com.thanhdatpb.listingguard.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

/** Development-only settings are refused outside the dev profile and never printed. */
class StartupSafetyCheckTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(StartupSafetyCheck.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLogs() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
    }

    private static MockEnvironment profiles(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    // ---- development key ----------------------------------------------------------------------------------

    @Test
    void aDevKeyIsRefusedWithoutAnyProfile() {
        assertThatThrownBy(() -> new StartupSafetyCheck(true, false, profiles()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LISTING_GUARD_DEV_API_KEY").hasMessageContaining("'dev' profile");
    }

    @Test
    void aDevKeyIsRefusedWithAnotherProfile() {
        assertThatThrownBy(() -> new StartupSafetyCheck(true, false, profiles("staging")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("[staging]");
    }

    @Test
    void aDevKeyIsRefusedWhenProdIsActiveEvenWithDev() {
        assertThatThrownBy(() -> new StartupSafetyCheck(true, false, profiles("dev", "prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("never with 'prod'");
    }

    @Test
    void aDevKeyIsAllowedWithTheDevProfile() {
        assertThatCode(() -> new StartupSafetyCheck(true, false, profiles("dev"))).doesNotThrowAnyException();
    }

    @Test
    void withoutADevKeyNoProfileIsNeeded() {
        assertThatCode(() -> new StartupSafetyCheck(false, false, profiles())).doesNotThrowAnyException();
        assertThatCode(() -> new StartupSafetyCheck(false, false, profiles("prod"))).doesNotThrowAnyException();
    }

    // ---- swagger flag -------------------------------------------------------------------------------------

    @Test
    void theSwaggerFlagLogsAWarningAndStarts() {
        new StartupSafetyCheck(false, true, profiles("dev"));

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getFormattedMessage()).contains("Swagger UI is enabled").contains("without an API key");
    }

    @Test
    void theSwaggerFlagWithoutProfilesAlsoJustWarns() {
        new StartupSafetyCheck(false, true, profiles());

        assertThat(warnings()).hasSize(1);
    }

    @Test
    void theSwaggerFlagIsRefusedUnderProd() {
        assertThatThrownBy(() -> new StartupSafetyCheck(false, true, profiles("prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LISTING_GUARD_SWAGGER_UI").hasMessageContaining("'prod'");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void noWarningWhenTheFlagIsOff() {
        new StartupSafetyCheck(false, false, profiles("prod"));

        assertThat(warnings()).isEmpty();
    }
}
