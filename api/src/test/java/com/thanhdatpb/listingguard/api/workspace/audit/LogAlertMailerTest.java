package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The {@code dev} profile (demo-local) writes the alert e-mail to the log, as it does the sign-in link (ADR-061). No database, nothing is sent. */
class LogAlertMailerTest {

    @Test
    void theEmailGoesToTheLogWithItsAddressSubjectAndBody() {
        Logger logger = (Logger) LoggerFactory.getLogger(LogAlertMailer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new LogAlertMailer().send("admin@example.test", "Listing Guard: 1 listing(s) with a new risk", "SKU | Account\nH-1 | Shop A");

            assertThat(appender.list).hasSize(1);
            String line = appender.list.get(0).getFormattedMessage();
            assertThat(line).contains("admin@example.test").contains("1 listing(s) with a new risk").contains("H-1 | Shop A");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
