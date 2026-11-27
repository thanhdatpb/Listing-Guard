package com.thanhdatpb.listingguard.api.workspace.audit;

/** Sends one plain-text alert e-mail (ADR-061). The SMTP one is {@link SmtpAlertMailer}; the {@code dev} profile logs the e-mail instead. */
public interface AlertMailer {

    void send(String to, String subject, String body);
}
