package com.thanhdatpb.listingguard.api.workspace.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The alert e-mail of the {@code dev} profile (the demo-local environment): it is written to the log, the way the sign-in link is, and
 * nothing is sent (ADR-061). It logs the text of the e-mail, which holds SKUs and short excerpts of listings; that is acceptable only
 * because the {@code dev} profile holds synthetic data and is never a production profile.
 */
@Component
@Profile("dev")
@AlertsEnabled
public class LogAlertMailer implements AlertMailer {

    private static final Logger log = LoggerFactory.getLogger(LogAlertMailer.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("Development alert e-mail to {}: {}\n{}", to, subject, body);
    }
}
