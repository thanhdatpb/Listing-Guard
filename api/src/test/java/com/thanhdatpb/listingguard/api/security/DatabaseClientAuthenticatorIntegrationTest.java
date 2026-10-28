package com.thanhdatpb.listingguard.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ApiKeyHasher;
import com.thanhdatpb.listingguard.api.client.ApiKeyRepository;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The cache and the throttled last-used write of {@link DatabaseClientAuthenticator}, with a clock the test moves. */
@SpringBootTest
@Import(TestcontainersConfig.class)
class DatabaseClientAuthenticatorIntegrationTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration LAST_USED_INTERVAL = Duration.ofMinutes(1);

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ApiKeyRepository keys;

    private final long[] nanos = {0};

    private DatabaseClientAuthenticator authenticator() {
        return new DatabaseClientAuthenticator(keys, TTL, LAST_USED_INTERVAL, () -> nanos[0]);
    }

    private void advance(Duration time) {
        nanos[0] += time.toNanos();
    }

    private static String newName() {
        return "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private ClientProvisioning.IssuedKey newKey(String name) {
        provisioning.createClient(name);
        return provisioning.createKey(name, null);
    }

    private Instant lastUsed(String keyId) {
        return keys.findByKeyId(keyId).orElseThrow().getLastUsedAt();
    }

    @Test
    void anAnswerIsRememberedForTheCacheTimeAndRevocationShowsAfterwards() {
        DatabaseClientAuthenticator authenticator = authenticator();
        ClientProvisioning.IssuedKey key = newKey(newName());
        assertThat(authenticator.authenticate(key.secret())).isPresent();

        provisioning.revokeKey(key.keyId());

        advance(TTL.minusSeconds(1));
        assertThat(authenticator.authenticate(key.secret())).as("still cached").isPresent();
        advance(Duration.ofSeconds(2));
        assertThat(authenticator.authenticate(key.secret())).as("cache expired, database says revoked").isEmpty();
    }

    @Test
    void anUnknownKeyIsRememberedToo() {
        DatabaseClientAuthenticator authenticator = authenticator();
        String secret = "later-registered-" + UUID.randomUUID();
        assertThat(authenticator.authenticate(secret)).isEmpty();

        provisioning.ensureKnownKey(newName(), secret, null);

        assertThat(authenticator.authenticate(secret)).as("cached as unknown").isEmpty();
        advance(TTL.plusSeconds(1));
        assertThat(authenticator.authenticate(secret)).isPresent();
    }

    @Test
    void lastUsedIsWrittenOnTheFirstUseAndThenAtMostOncePerInterval() throws Exception {
        DatabaseClientAuthenticator authenticator = authenticator();
        ClientProvisioning.IssuedKey key = newKey(newName());
        assertThat(lastUsed(key.keyId())).isNull();

        authenticator.authenticate(key.secret());
        Instant first = lastUsed(key.keyId());
        assertThat(first).isNotNull();

        TimeUnit.MILLISECONDS.sleep(20);
        for (int i = 0; i < 5; i++) {
            advance(Duration.ofSeconds(10));
            authenticator.authenticate(key.secret());
        }
        assertThat(lastUsed(key.keyId())).as("50 seconds later: no new write").isEqualTo(first);

        advance(Duration.ofSeconds(11));
        authenticator.authenticate(key.secret());
        assertThat(lastUsed(key.keyId())).as("more than a minute after the write").isAfter(first);
    }

    @Test
    void aFailedLastUsedWriteDoesNotFailTheRequest() {
        ClientProvisioning.IssuedKey key = newKey(newName());
        ApiKeyRepository broken = mock(ApiKeyRepository.class, CALLS_REAL_METHODS);
        doReturn(keys.findByKeyHashWithClient(ApiKeyHasher.sha256Hex(key.secret()))).when(broken).findByKeyHashWithClient(anyString());
        doThrow(new IllegalStateException("database is read only")).when(broken).touch(anyString(), any());

        assertThat(new DatabaseClientAuthenticator(broken, TTL, LAST_USED_INTERVAL, () -> nanos[0]).authenticate(key.secret())).isPresent();
    }

    @Test
    void blankKeysAreNeverAuthenticated() {
        DatabaseClientAuthenticator authenticator = authenticator();

        assertThat(authenticator.authenticate(null)).isEmpty();
        assertThat(authenticator.authenticate("")).isEmpty();
    }
}
