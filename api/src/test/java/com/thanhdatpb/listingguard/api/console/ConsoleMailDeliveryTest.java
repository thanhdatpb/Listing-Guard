package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** The sign-in e-mail is in the language of the page the link was asked for on (ADR-028) and carries the link once. */
class ConsoleMailDeliveryTest {

    private static final String LINK = "https://console.example.test/login/verify?token=abc";

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final ConsoleMailDelivery delivery = new ConsoleMailDelivery(sender,
            new ConsoleProperties(true, "https://console.example.test", true, "noreply@example.test", 5, 30, Duration.ofMinutes(15), 5,
                    "en"),
            ConsoleMessagesTestSupport.messages());

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private SimpleMailMessage sent(Locale locale) {
        LocaleContextHolder.setLocale(locale);
        delivery.send("seller@example.test", LINK);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void anEnglishPageGetsTheMailTheConsoleSentBeforeItWasTranslated() {
        SimpleMailMessage mail = sent(Locale.ENGLISH);

        assertThat(mail.getSubject()).isEqualTo("Your Listing Guard sign-in link");
        assertThat(mail.getText()).isEqualTo("Use this link to sign in to Listing Guard. It expires in 15 minutes:\n\n" + LINK
                + "\n\nIf you did not request this link, you can ignore this email.");
        assertThat(mail.getFrom()).isEqualTo("noreply@example.test");
        assertThat(mail.getTo()).containsExactly("seller@example.test");
    }

    @Test
    void aVietnamesePageGetsAVietnameseMailWithTheSameLinkOnce() {
        SimpleMailMessage mail = sent(Locale.forLanguageTag("vi"));

        assertThat(mail.getSubject()).isEqualTo("Liên kết đăng nhập Listing Guard của bạn");
        assertThat(mail.getText()).contains("15 phút").contains("bỏ qua email này");
        assertThat(mail.getText().split(Pattern.quote(LINK), -1)).hasSize(2);
    }
}
