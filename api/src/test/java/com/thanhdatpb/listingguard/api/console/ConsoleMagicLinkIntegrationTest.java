package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.LoggerFactory;

@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleMagicLinkIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MagicLinkService magicLinks;

    @Autowired
    ConsoleLoginRetentionCleanup retentionCleanup;

    @Autowired
    Environment environment;

    @MockitoBean
    JavaMailSender mailSender;

    @Test
    void configuredConsoleDoesNotChangeApiKeyAuthentication() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in to Listing Guard")));

        mvc.perform(get("/api/v1/checks/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/checks/" + UUID.randomUUID()).header(HEADER, KEY_1))
                .andExpect(status().isNotFound());
    }

    @Test
    void getVerifyIsReadOnlyAndUsesConfiguredBaseUrlThenPostRotatesSessionAndSignsIn() throws Exception {
        String email = "Console-" + UUID.randomUUID() + "@Example.test";
        String token = requestLink(email, "spoofed.example.test");
        assertTokenIsStoredOnlyAsHash(token);

        mvc.perform(get("/login/verify")
                        .header("Host", "attacker.example")
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in")));
        assertThat(tokenConsumed(token)).isFalse();

        MvcResult loginPage = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) loginPage.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(environment.getProperty("server.servlet.session.cookie.name")).isEqualTo("LISTINGGUARDSESSION");
        assertThat(environment.getProperty("server.servlet.session.cookie.http-only", Boolean.class)).isTrue();
        assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
        assertThat(environment.getProperty("server.tomcat.accesslog.pattern"))
                .contains("%U").doesNotContain("%r");
        String sessionIdBefore = session.getId();

        mvc.perform(post("/login/verify").session(session).param("token", token))
                .andExpect(status().isForbidden());
        assertThat(tokenConsumed(token)).isFalse();

        MvcResult signedIn = mvc.perform(post("/login/verify").session(session).with(csrf()).param("token", token))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/console"))
                .andReturn();
        MockHttpSession authenticatedSession = (MockHttpSession) signedIn.getRequest().getSession(false);
        assertThat(authenticatedSession).isNotNull();
        assertThat(authenticatedSession.getId()).isNotEqualTo(sessionIdBefore);
        assertThat(tokenConsumed(token)).isTrue();

        String normalizedEmail = email.toLowerCase(java.util.Locale.ROOT);
        UUID clientId = jdbc.queryForObject("SELECT client_id FROM account WHERE email = ?", UUID.class, normalizedEmail);
        assertThat(jdbc.queryForObject("SELECT plan_code FROM client WHERE id = ?", String.class, clientId)).isEqualTo("FREE");
        mvc.perform(get("/console").session(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(normalizedEmail)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FREE")));

        mvc.perform(post("/login").with(csrf()).param("email", email))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "If an account can be created or signed in for that address, a sign-in link will be sent.")));

        mvc.perform(post("/login/verify").session(authenticatedSession).with(csrf()).param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("expired or has already been used")));
        mvc.perform(post("/logout").session(authenticatedSession).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login?logout"));
        assertThat(authenticatedSession.isInvalid()).isTrue();
        mvc.perform(get("/console"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login"));
    }

    @Test
    void loginFormUsesTheSameConfirmationAndEnforcesFiveRequestsPerEmail() throws Exception {
        String email = "limited-" + UUID.randomUUID() + "@example.test";
        for (int attempt = 0; attempt < 6; attempt++) {
            mvc.perform(post("/login").with(csrf())
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.78");
                                return request;
                            })
                            .param("email", email))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "If an account can be created or signed in for that address, a sign-in link will be sent.")));
        }
        verify(mailSender, times(5)).send(any(SimpleMailMessage.class));
    }

    @Test
    void loginRateLimitUsesTheRemoteAddressRatherThanForwardedHeaders() throws Exception {
        for (int attempt = 0; attempt < 31; attempt++) {
            mvc.perform(post("/login")
                            .with(csrf())
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.77");
                                return request;
                            })
                            .header("X-Forwarded-For", "192.0.2." + (attempt + 1))
                            .param("email", "ip-" + UUID.randomUUID() + "@example.test"))
                    .andExpect(status().isOk());
        }
        verify(mailSender, times(30)).send(any(SimpleMailMessage.class));
    }

    @Test
    void simultaneousFirstSignInsForOneEmailCreateOneFreeClient() throws Exception {
        String email = "concurrent-" + UUID.randomUUID() + "@example.test";
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/login").with(csrf()).param("email", email))
                    .andExpect(status().isOk());
        }

        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(2)).send(captor.capture());
        List<String> tokens = captor.getAllValues().stream().map(this::tokenFromMail).toList();
        assertThat(tokens).hasSize(2).doesNotHaveDuplicates();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<ConsoleUser>> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return magicLinks.consume(tokens.get(0));
            });
            Future<Optional<ConsoleUser>> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return magicLinks.consume(tokens.get(1));
            });
            ready.await();
            start.countDown();
            ConsoleUser firstUser = first.get().orElseThrow();
            ConsoleUser secondUser = second.get().orElseThrow();

            assertThat(firstUser.clientId()).isEqualTo(secondUser.clientId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE email = ?",
                    Integer.class, email)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT plan_code FROM client WHERE id = ?",
                    String.class, firstUser.clientId())).isEqualTo("FREE");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void existingAndNewEmailReceiveTheSameNeutralConfirmation() throws Exception {
        String email = "enumeration-" + UUID.randomUUID() + "@example.test";
        MvcResult newAddress = mvc.perform(post("/login").with(csrf()).param("email", email))
                .andExpect(status().isOk())
                .andReturn();

        var mailCaptor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mailCaptor.capture());
        ConsoleUser user = magicLinks.consume(tokenFromMail(mailCaptor.getValue())).orElseThrow();
        assertThat(user.email()).isEqualTo(email);

        MvcResult existingAddress = mvc.perform(post("/login").with(csrf()).param("email", email))
                .andExpect(status().isOk())
                .andReturn();
        String neutralMessage =
                "If an account can be created or signed in for that address, a sign-in link will be sent.";
        assertThat(newAddress.getResponse().getContentAsString()).contains(neutralMessage);
        assertThat(existingAddress.getResponse().getContentAsString()).contains(neutralMessage);
    }

    @Test
    void tokensAreNotWrittenToApplicationLogsOutsideDev() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            magicLinks.requestSignIn("no-log-" + UUID.randomUUID() + "@example.test", "203.0.113.99");
            var mailCaptor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(mailSender).send(mailCaptor.capture());
            String token = tokenFromMail(mailCaptor.getValue());

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(token));
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void tokenExpiresAfterFifteenMinutesAndCleanupRemovesExpiredTokenData() throws Exception {
        String token = requestLink("expires-" + UUID.randomUUID() + "@example.test", "spoofed.example.test");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        Double lifetimeSeconds = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM expires_at - created_at) FROM login_token WHERE token_hash = ?",
                Double.class, hash);
        assertThat(lifetimeSeconds).isEqualTo(Duration.ofMinutes(15).toSeconds());

        jdbc.update("UPDATE login_token SET created_at = now() - interval '16 minutes', "
                + "expires_at = now() - interval '1 second' WHERE token_hash = ?", hash);
        assertThat(magicLinks.isValid(token)).isFalse();
        assertThat(magicLinks.consume(token)).isEmpty();
        retentionCleanup.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_token WHERE token_hash = ?", Integer.class, hash))
                .isZero();
    }

    private String requestLink(String email, String hostHeader) throws Exception {
        mvc.perform(post("/login").with(csrf()).header("Host", hostHeader).param("email", email))
                .andExpect(status().isOk());
        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        String link = linkFromMail(captor.getValue());
        assertThat(link).doesNotContain(hostHeader);
        return UriComponentsBuilder.fromUriString(link).build().getQueryParams().getFirst("token");
    }

    private String tokenFromMail(SimpleMailMessage message) {
        return UriComponentsBuilder.fromUriString(linkFromMail(message)).build().getQueryParams().getFirst("token");
    }

    private String linkFromMail(SimpleMailMessage message) {
        return java.util.regex.Pattern.compile("(https://console\\.example\\.test/login/verify\\?token=[A-Za-z0-9_-]{43})")
                .matcher(message.getText()).results().map(result -> result.group()).findFirst().orElseThrow();
    }

    private void assertTokenIsStoredOnlyAsHash(String token) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("SELECT token_hash FROM login_token WHERE token_hash = ?", String.class, hash))
                .isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_token WHERE token_hash = ?", Integer.class, token))
                .isZero();
    }

    private boolean tokenConsumed(String token) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        return jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM login_token WHERE token_hash = ?",
                Boolean.class, hash);
    }
}
