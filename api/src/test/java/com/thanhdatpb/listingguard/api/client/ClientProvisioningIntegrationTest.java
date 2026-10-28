package com.thanhdatpb.listingguard.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.security.ClientAuthenticator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Clients and keys against the real schema. The authenticator runs without a cache here (see TestcontainersConfig). */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ClientProvisioningIntegrationTest {

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientAuthenticator authenticator;

    @Autowired
    ApiKeyRepository keys;

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

    private static String newName() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private List<String> auditLines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void aNewClientIsActiveAndItsNameIsUnique() {
        String name = newName();

        Client client = provisioning.createClient(name);

        assertThat(client.isActive()).isTrue();
        assertThat(provisioning.clients()).extracting(Client::getName).contains(name);
        assertThatThrownBy(() -> provisioning.createClient(name)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
    }

    @Test
    void invalidClientNamesAreRefused() {
        for (String bad : new String[] {"", "Upper", "-dash", "has space", "x".repeat(33)}) {
            assertThatThrownBy(() -> provisioning.createClient(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anIssuedKeyAuthenticatesAndOnlyItsHashIsStored() {
        String name = newName();
        provisioning.createClient(name);

        ClientProvisioning.IssuedKey issued = provisioning.createKey(name, "primary");

        assertThat(issued.secret()).matches("lg_[A-Za-z0-9_-]{43}");
        assertThat(issued.keyId()).matches("[a-z0-9]{8}");
        assertThat(authenticator.authenticate(issued.secret())).hasValueSatisfying(client -> {
            assertThat(client.name()).isEqualTo(name);
            assertThat(client.keyId()).isEqualTo(issued.keyId());
        });
        ApiKey stored = keys.findByKeyId(issued.keyId()).orElseThrow();
        assertThat(stored.getKeyHash()).isEqualTo(ApiKeyHasher.sha256Hex(issued.secret())).isNotEqualTo(issued.secret());
        assertThat(stored.getLabel()).isEqualTo("primary");
    }

    @Test
    void aClientCanHoldSeveralKeysAtOnce() {
        String name = newName();
        provisioning.createClient(name);

        ClientProvisioning.IssuedKey first = provisioning.createKey(name, "old");
        ClientProvisioning.IssuedKey second = provisioning.createKey(name, "new");

        assertThat(authenticator.authenticate(first.secret())).isPresent();
        assertThat(authenticator.authenticate(second.secret())).isPresent();
        assertThat(provisioning.keys(name)).extracting(ApiKey::getKeyId).containsExactly(first.keyId(), second.keyId());
    }

    @Test
    void aKeyForAnUnknownClientIsRefused() {
        assertThatThrownBy(() -> provisioning.createKey(newName(), null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No client named");
    }

    @Test
    void deactivatingAClientStopsAllItsKeysAndActivatingBringsThemBack() {
        String name = newName();
        provisioning.createClient(name);
        String secret = provisioning.createKey(name, null).secret();

        provisioning.setActive(name, false);
        assertThat(authenticator.authenticate(secret)).isEmpty();

        provisioning.setActive(name, true);
        assertThat(authenticator.authenticate(secret)).isPresent();
    }

    @Test
    void aRevokedKeyStopsWorkingAndTheOtherKeysOfTheClientKeepWorking() {
        String name = newName();
        provisioning.createClient(name);
        ClientProvisioning.IssuedKey revoked = provisioning.createKey(name, null);
        ClientProvisioning.IssuedKey kept = provisioning.createKey(name, null);

        ApiKey result = provisioning.revokeKey(revoked.keyId());

        assertThat(result.isActive()).isFalse();
        assertThat(result.getRevokedAt()).isNotNull();
        assertThat(authenticator.authenticate(revoked.secret())).isEmpty();
        assertThat(authenticator.authenticate(kept.secret())).isPresent();
        assertThatThrownBy(() -> provisioning.revokeKey(revoked.keyId())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already revoked");
        assertThatThrownBy(() -> provisioning.revokeKey("nokey000")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No key");
    }

    @Test
    void everyChangeIsAuditedWithClientKeyIdAndTimeAndNeverTheSecret() {
        String name = newName();

        provisioning.createClient(name);
        ClientProvisioning.IssuedKey issued = provisioning.createKey(name, null);
        provisioning.setActive(name, false);
        provisioning.setActive(name, true);
        provisioning.revokeKey(issued.keyId());

        List<String> lines = auditLines();
        assertThat(lines).hasSize(5);
        assertThat(lines.get(0)).startsWith("audit action=client.create client=" + name + " keyId=- at=");
        assertThat(lines.get(1)).startsWith("audit action=key.create client=" + name + " keyId=" + issued.keyId() + " at=");
        assertThat(lines.get(2)).startsWith("audit action=client.deactivate client=" + name);
        assertThat(lines.get(3)).startsWith("audit action=client.activate client=" + name);
        assertThat(lines.get(4)).startsWith("audit action=key.revoke client=" + name + " keyId=" + issued.keyId() + " at=");
        assertThat(lines).allSatisfy(line -> {
            assertThat(line).matches(".* at=\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z");
            assertThat(line).doesNotContain(issued.secret());
        });
    }

    @Test
    void ensureKnownKeyIsIdempotentAndQuietTheSecondTime() {
        String name = newName();
        String secret = "known-secret-" + UUID.randomUUID();

        assertThat(provisioning.ensureKnownKey(name, secret, "fixed")).isTrue();
        int linesAfterFirst = auditLines().size();
        assertThat(provisioning.ensureKnownKey(name, secret, "fixed")).isFalse();

        assertThat(auditLines()).hasSize(linesAfterFirst);
        assertThat(authenticator.authenticate(secret)).hasValueSatisfying(client -> assertThat(client.name()).isEqualTo(name));
    }

    @Test
    void aMalformedKnownKeyIsRefusedWithoutEchoingIt() {
        String tooShort = "short-secret";

        assertThatThrownBy(() -> provisioning.ensureKnownKey(newName(), tooShort, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not valid").hasMessageNotContaining(tooShort);
    }
}
