package com.thanhdatpb.listingguard.api.console;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@Profile("!dev")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleMailDelivery implements MagicLinkDelivery {

    private final JavaMailSender mailSender;
    private final ConsoleProperties properties;
    private final ConsoleMessages messages;

    public ConsoleMailDelivery(JavaMailSender mailSender, ConsoleProperties properties, ConsoleMessages messages) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.messages = messages;
    }

    /**
     * Called while the sign-in request is being handled, so the e-mail is in the language of the page on which the link was
     * asked for (ADR-028).
     */
    @Override
    public void send(String email, String link) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject(messages.get("mail.subject"));
        message.setText(messages.get("mail.body.before") + "\n\n" + link + "\n\n" + messages.get("mail.body.after"));
        mailSender.send(message);
    }
}
