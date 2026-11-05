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

    public ConsoleMailDelivery(JavaMailSender mailSender, ConsoleProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(String email, String link) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("Your Listing Guard sign-in link");
        message.setText("Use this link to sign in to Listing Guard. It expires in 15 minutes:\n\n" + link
                + "\n\nIf you did not request this link, you can ignore this email.");
        mailSender.send(message);
    }
}
