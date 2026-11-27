package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.api.console.ConsoleProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * The alert e-mail through the SMTP server the Console already uses for sign-in links ({@code spring.mail.*}, {@code listing-guard.console.mail-from})
 * (ADR-061). Plain text only: a listing's text has no place to become markup.
 */
@Component
@Profile("!dev")
@AlertsEnabled
public class SmtpAlertMailer implements AlertMailer {

    private final JavaMailSender mailSender;
    private final ConsoleProperties console;

    public SmtpAlertMailer(JavaMailSender mailSender, ConsoleProperties console) {
        this.mailSender = mailSender;
        this.console = console;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(console.mailFrom());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
