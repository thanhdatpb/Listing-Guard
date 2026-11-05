package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class MagicLinkService {

    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);
    private static final Duration TOKEN_RETENTION = Duration.ofDays(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConsoleAccountRepository accounts;
    private final ClientRepository clients;
    private final ConsoleProperties properties;
    private final MagicLinkDelivery delivery;
    private final Clock clock;

    public MagicLinkService(JdbcTemplate jdbc, TransactionTemplate transactions, ConsoleAccountRepository accounts,
                            ClientRepository clients, ConsoleProperties properties, MagicLinkDelivery delivery, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.accounts = accounts;
        this.clients = clients;
        this.properties = properties;
        this.delivery = delivery;
        this.clock = clock;
    }

    public void requestSignIn(String submittedEmail, String remoteAddress) {
        String email = normalizeEmail(submittedEmail);
        String token = transactions.execute(status -> {
            Instant now = clock.instant();
            Instant windowStart = windowStart(now, properties.rateWindow());
            jdbc.update("DELETE FROM login_token WHERE created_at < ?", Timestamp.from(now.minus(TOKEN_RETENTION)));
            jdbc.update("DELETE FROM console_login_rate_limit WHERE window_start < ?",
                    Timestamp.from(windowStart.minus(properties.rateWindow().multipliedBy(2))));
            if (!incrementRateLimit("EMAIL", sha256Hex(email), windowStart, properties.emailLimit())
                    || !incrementRateLimit("IP", sha256Hex(remoteAddress), windowStart, properties.ipLimit())) {
                status.setRollbackOnly();
                return null;
            }

            String issuedToken = newToken();
            jdbc.update("INSERT INTO login_token (token_hash, email, created_at, expires_at) VALUES (?, ?, ?, ?)",
                    sha256Hex(issuedToken), email, Timestamp.from(now), Timestamp.from(now.plus(TOKEN_LIFETIME)));
            return issuedToken;
        });
        if (token != null) {
            delivery.send(email, buildLink(token));
        }
    }

    public boolean isValid(String token) {
        if (!validTokenShape(token)) {
            return false;
        }
        Integer found = jdbc.query("SELECT 1 FROM login_token WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ?",
                rs -> rs.next() ? rs.getInt(1) : null, sha256Hex(token), Timestamp.from(clock.instant()));
        return found != null;
    }

    public Optional<ConsoleUser> consume(String token) {
        if (!validTokenShape(token)) {
            return Optional.empty();
        }
        return Optional.ofNullable(transactions.execute(status -> {
            Instant now = clock.instant();
            String tokenHash = sha256Hex(token);
            String email = jdbc.query("UPDATE login_token SET consumed_at = ? "
                            + "WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ? RETURNING email",
                    rs -> rs.next() ? rs.getString("email") : null,
                    Timestamp.from(now), tokenHash, Timestamp.from(now));
            if (email == null) {
                return null;
            }

            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
                rs.next();
                return null;
            }, "console-account:" + email);

            ConsoleAccount account = accounts.findByEmail(email).orElseGet(() -> {
                String clientName = "web-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                Client client = clients.saveAndFlush(new Client(clientName));
                return accounts.saveAndFlush(new ConsoleAccount(email, client.getId()));
            });
            return new ConsoleUser(account.getEmail(), account.getClientId());
        }));
    }

    static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private boolean incrementRateLimit(String scope, String keyHash, Instant windowStart, int limit) {
        return jdbc.update("""
                INSERT INTO console_login_rate_limit (scope, key_hash, window_start, attempts)
                VALUES (?, ?, ?, 1)
                ON CONFLICT (scope, key_hash, window_start) DO UPDATE
                SET attempts = console_login_rate_limit.attempts + 1
                WHERE console_login_rate_limit.attempts < ?
                """, scope, keyHash, Timestamp.from(windowStart), limit) == 1;
    }

    private String buildLink(String token) {
        String base = properties.baseUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/login/verify?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private static Instant windowStart(Instant now, Duration window) {
        long seconds = window.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), seconds) * seconds);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean validTokenShape(String token) {
        return token != null && token.length() == 43 && token.matches("[A-Za-z0-9_-]{43}");
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}
