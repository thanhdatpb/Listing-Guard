package com.thanhdatpb.listingguard.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning.IssuedKey;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning.KeyLimitReachedException;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning.KeyNotFoundException;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning.RevokeOutcome;
import com.thanhdatpb.listingguard.api.security.ClientAuthenticator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The key methods that take a client id, used by the console (ADR-014 B4a): the key limit, the lock that makes it hold under
 * concurrent requests, the ownership check, and the audit lines, which are the ones of the name-based methods.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ClientProvisioningByIdIntegrationTest {

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    ApiKeyRepository keys;

    @Autowired
    ClientAuthenticator authenticator;

    private final Logger auditLogger = (Logger) LoggerFactory.getLogger("com.thanhdatpb.listingguard.audit");
    private ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void captureAudit() {
        audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
    }

    @AfterEach
    void releaseAudit() {
        auditLogger.detachAppender(audit);
    }

    private Client newClient() {
        return provisioning.createClient("t" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
    }

    private List<String> auditLines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void aKeyIssuedByClientIdAuthenticatesAsThatClientAndOnlyItsHashIsStored() {
        Client client = newClient();

        IssuedKey issued = provisioning.createKey(client.getId(), "preview app", 5);

        assertThat(issued.secret()).matches("lg_[A-Za-z0-9_-]{43}");
        assertThat(authenticator.authenticate(issued.secret())).hasValueSatisfying(found -> {
            assertThat(found.name()).isEqualTo(client.getName());
            assertThat(found.keyId()).isEqualTo(issued.keyId());
        });
        ApiKey stored = keys.findByKeyId(issued.keyId()).orElseThrow();
        assertThat(stored.getKeyHash()).isEqualTo(ApiKeyHasher.sha256Hex(issued.secret())).isNotEqualTo(issued.secret());
        assertThat(stored.getLabel()).isEqualTo("preview app");
    }

    @Test
    void theAuditLinesAreTheOnesOfTheNameBasedMethodsAndNeverHoldTheSecret() {
        Client client = newClient();
        audit.list.clear();

        IssuedKey issued = provisioning.createKey(client.getId(), null, 5);
        provisioning.revokeKey(client.getId(), issued.keyId());

        assertThat(auditLines()).hasSize(2);
        assertThat(auditLines().get(0)).startsWith("audit action=key.create client=" + client.getName() + " keyId=" + issued.keyId() + " at=");
        assertThat(auditLines().get(1)).startsWith("audit action=key.revoke client=" + client.getName() + " keyId=" + issued.keyId() + " at=");
        assertThat(String.join("\n", auditLines())).doesNotContain(issued.secret());
    }

    @Test
    void theLimitCountsActiveKeysOnlySoARevokedKeyFreesAPlace() {
        Client client = newClient();
        provisioning.createKey(client.getId(), "one", 2);
        IssuedKey second = provisioning.createKey(client.getId(), "two", 2);

        assertThatThrownBy(() -> provisioning.createKey(client.getId(), "three", 2))
                .isInstanceOfSatisfying(KeyLimitReachedException.class, ex -> assertThat(ex.limit()).isEqualTo(2));
        assertThat(provisioning.activeKeys(client.getId())).hasSize(2);

        provisioning.revokeKey(client.getId(), second.keyId());
        provisioning.createKey(client.getId(), "three", 2);

        assertThat(provisioning.activeKeys(client.getId())).extracting(ApiKey::getLabel).containsExactly("one", "three");
    }

    @Test
    void theLimitIsOnlyOfTheClientAndKeysMadeByTheOperatorCount() {
        Client busy = newClient();
        Client other = newClient();
        provisioning.createKey(busy.getName(), "by the operator");

        assertThatThrownBy(() -> provisioning.createKey(busy.getId(), "from the console", 1)).isInstanceOf(KeyLimitReachedException.class);
        provisioning.createKey(other.getId(), "from the console", 1);
    }

    @Test
    void concurrentRequestsCannotTakeAClientPastTheLimit() throws Exception {
        Client client = newClient();
        int limit = 3;
        int attempts = 12;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                Callable<Boolean> attempt = () -> {
                    start.await();
                    try {
                        provisioning.createKey(client.getId(), null, limit);
                        return true;
                    } catch (KeyLimitReachedException refused) {
                        return false;
                    }
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            int created = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(limit);
            assertThat(provisioning.activeKeys(client.getId())).hasSize(limit);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aKeyOfAnotherClientIsNotFoundAndStaysActive() {
        Client owner = newClient();
        Client stranger = newClient();
        IssuedKey issued = provisioning.createKey(owner.getId(), null, 5);
        audit.list.clear();

        assertThatThrownBy(() -> provisioning.revokeKey(stranger.getId(), issued.keyId())).isInstanceOf(KeyNotFoundException.class);
        assertThatThrownBy(() -> provisioning.revokeKey(owner.getId(), "nokey000")).isInstanceOf(KeyNotFoundException.class);

        assertThat(authenticator.authenticate(issued.secret())).isPresent();
        assertThat(auditLines()).noneMatch(line -> line.contains("key.revoke"));
    }

    @Test
    void revokingAKeyTwiceIsAnswerOnceAndLoggedOnce() {
        Client client = newClient();
        IssuedKey issued = provisioning.createKey(client.getId(), null, 5);
        audit.list.clear();

        assertThat(provisioning.revokeKey(client.getId(), issued.keyId())).isEqualTo(RevokeOutcome.REVOKED);
        assertThat(provisioning.revokeKey(client.getId(), issued.keyId())).isEqualTo(RevokeOutcome.ALREADY_REVOKED);

        assertThat(authenticator.authenticate(issued.secret())).isEmpty();
        assertThat(auditLines()).filteredOn(line -> line.contains("key.revoke")).hasSize(1);
        assertThat(provisioning.activeKeys(client.getId())).isEmpty();
    }

    @Test
    void anUnusableRequestIsRefusedBeforeAnythingIsStored() {
        Client client = newClient();

        assertThatThrownBy(() -> provisioning.createKey(UUID.randomUUID(), null, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provisioning.createKey(client.getId(), null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provisioning.createKey(client.getId(), "x".repeat(ClientProvisioning.MAX_LABEL_LENGTH + 1), 5))
                .isInstanceOf(IllegalArgumentException.class);
        provisioning.createKey(client.getId(), "x".repeat(ClientProvisioning.MAX_LABEL_LENGTH), 5);

        assertThat(provisioning.activeKeys(client.getId())).hasSize(1);
    }
}
