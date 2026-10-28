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

class StartupSafetyCheckTest {

    private static final String DEV_KEY = "insecure-dev-key-change-me-0123456789abcdef";
    private static final ApiKeys INSECURE = ApiKeys.parse(List.of("dev:" + DEV_KEY));
    private static final ApiKeys REAL = ApiKeys.parse(List.of("preview:" + "k".repeat(40)));

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

    // ---- development keys ---------------------------------------------------------------------------------

    @Test
    void anInsecureKeyIsRefusedWithoutAnyProfile() {
        assertThatThrownBy(() -> new StartupSafetyCheck(INSECURE, false, profiles()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insecure-").hasMessageContaining("'dev' profile")
                .hasMessageNotContaining(DEV_KEY);
    }

    @Test
    void anInsecureKeyIsRefusedWithAnotherProfile() {
        assertThatThrownBy(() -> new StartupSafetyCheck(INSECURE, false, profiles("staging")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("[staging]");
    }

    @Test
    void anInsecureKeyIsRefusedWhenProdIsActiveEvenWithDev() {
        assertThatThrownBy(() -> new StartupSafetyCheck(INSECURE, false, profiles("dev", "prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("never with 'prod'");
    }

    @Test
    void anInsecureKeyIsAllowedWithTheDevProfile() {
        assertThatCode(() -> new StartupSafetyCheck(INSECURE, false, profiles("dev"))).doesNotThrowAnyException();
    }

    @Test
    void aKeyThatDoesNotStartWithInsecureNeedsNoProfile() {
        assertThat(REAL.hasInsecureDevKey()).isFalse();
        assertThatCode(() -> new StartupSafetyCheck(REAL, false, profiles())).doesNotThrowAnyException();
        assertThatCode(() -> new StartupSafetyCheck(REAL, false, profiles("prod"))).doesNotThrowAnyException();
    }

    @Test
    void oneInsecureKeyAmongSeveralIsEnoughToRefuse() {
        ApiKeys mixed = ApiKeys.parse(List.of("preview:" + "k".repeat(40), "dev:" + DEV_KEY));

        assertThat(mixed.hasInsecureDevKey()).isTrue();
        assertThatThrownBy(() -> new StartupSafetyCheck(mixed, false, profiles("prod"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyTheKeyCountsNotTheName() {
        ApiKeys namedInsecure = ApiKeys.parse(List.of("insecure-name:" + "k".repeat(40)));

        assertThat(namedInsecure.hasInsecureDevKey()).isFalse();
    }

    // ---- swagger flag -------------------------------------------------------------------------------------

    @Test
    void theSwaggerFlagLogsAWarningAndStarts() {
        new StartupSafetyCheck(REAL, true, profiles("dev"));

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getFormattedMessage()).contains("Swagger UI is enabled").contains("without an API key");
    }

    @Test
    void theSwaggerFlagWithoutProfilesAlsoJustWarns() {
        new StartupSafetyCheck(REAL, true, profiles());

        assertThat(warnings()).hasSize(1);
    }

    @Test
    void theSwaggerFlagIsRefusedUnderProd() {
        assertThatThrownBy(() -> new StartupSafetyCheck(REAL, true, profiles("prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LISTING_GUARD_SWAGGER_UI").hasMessageContaining("'prod'");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void noWarningWhenTheFlagIsOff() {
        new StartupSafetyCheck(REAL, false, profiles("prod"));

        assertThat(warnings()).isEmpty();
    }
}
