package com.thanhdatpb.listingguard.api.client;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates and changes clients and API keys. Used by the admin job, by the development key seed and by tests; there
 * is no HTTP endpoint for it (ADR-007).
 *
 * <p>Every change is written to the audit logger with the client, the key id and the time. A secret is never
 * logged, never part of an exception message, and returned only once, by {@link #createKey}.
 */
@Service
public class ClientProvisioning {

    public static final int MIN_SECRET_LENGTH = 32;

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9._~+/=-]{" + MIN_SECRET_LENGTH + ",256}");
    private static final String KEY_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int KEY_ID_LENGTH = 8;
    private static final String KEY_PREFIX = "lg_";

    private static final Logger audit = LoggerFactory.getLogger("com.thanhdatpb.listingguard.audit");

    /** A freshly issued key: the only moment the secret is available. */
    public record IssuedKey(String keyId, String secret) {
    }

    private final ClientRepository clients;
    private final ApiKeyRepository keys;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public ClientProvisioning(ClientRepository clients, ApiKeyRepository keys, TransactionTemplate transactions) {
        this.clients = clients;
        this.keys = keys;
        this.transactions = transactions;
    }

    /** @throws IllegalArgumentException if the name is not valid or already used */
    public Client createClient(String name) {
        requireValidName(name);
        Client created = transactions.execute(status -> {
            if (clients.findByName(name).isPresent()) {
                throw new IllegalArgumentException("Client '" + name + "' already exists.");
            }
            return clients.save(new Client(name));
        });
        audit("client.create", name, null);
        return created;
    }

    /** @throws IllegalArgumentException if there is no such client */
    public Client setActive(String name, boolean active) {
        Client changed = transactions.execute(status -> {
            Client client = requireClient(name);
            client.setActive(active);
            return clients.save(client);
        });
        audit(active ? "client.activate" : "client.deactivate", name, null);
        return changed;
    }

    /** Issues a new random key for the client. The secret in the result is shown once and cannot be read again. */
    public IssuedKey createKey(String clientName, String label) {
        String secret = KEY_PREFIX + randomSecret();
        String keyId = transactions.execute(status -> store(requireClient(clientName), secret, label));
        audit("key.create", clientName, keyId);
        return new IssuedKey(keyId, secret);
    }

    /**
     * Makes sure a key with this exact secret exists for the client, creating the client if needed. For the
     * development key and for tests, where the secret is chosen by the caller. Changes nothing (and logs nothing)
     * when the key is already known.
     *
     * @return true when a key was added
     */
    public boolean ensureKnownKey(String clientName, String secret, String label) {
        requireValidName(clientName);
        if (secret == null || !SECRET.matcher(secret).matches()) {
            throw new IllegalArgumentException("The key is not valid: it needs " + MIN_SECRET_LENGTH + " to 256 characters from "
                    + "A-Z a-z 0-9 . _ ~ + / = -");
        }
        record Added(boolean clientCreated, String keyId) {
        }
        Added added = transactions.execute(status -> {
            if (keys.findByKeyHash(ApiKeyHasher.sha256Hex(secret)).isPresent()) {
                return null;
            }
            boolean clientCreated = clients.findByName(clientName).isEmpty();
            Client client = clients.findByName(clientName).orElseGet(() -> clients.save(new Client(clientName)));
            return new Added(clientCreated, store(client, secret, label));
        });
        if (added == null) {
            return false;
        }
        if (added.clientCreated()) {
            audit("client.create", clientName, null);
        }
        audit("key.register", clientName, added.keyId());
        return true;
    }

    /** @throws IllegalArgumentException if there is no such key or it is already revoked */
    public ApiKey revokeKey(String keyId) {
        record Revoked(ApiKey key, String clientName) {
        }
        Revoked revoked = transactions.execute(status -> {
            ApiKey key = keys.findByKeyId(keyId).orElseThrow(() -> new IllegalArgumentException("No key with id '" + keyId + "'."));
            if (!key.isActive()) {
                throw new IllegalArgumentException("Key '" + keyId + "' is already revoked.");
            }
            key.revoke(Instant.now());
            return new Revoked(keys.save(key), key.getClient().getName());
        });
        audit("key.revoke", revoked.clientName(), keyId);
        return revoked.key();
    }

    public List<Client> clients() {
        return clients.findAllByOrderByNameAsc();
    }

    /** @throws IllegalArgumentException if there is no such client */
    public List<ApiKey> keys(String clientName) {
        Client client = requireClient(clientName);
        return keys.findByClient_IdOrderByCreatedAtAsc(client.getId());
    }

    private String store(Client client, String secret, String label) {
        String keyId = newKeyId();
        keys.save(new ApiKey(client, keyId, ApiKeyHasher.sha256Hex(secret), label));
        return keyId;
    }

    private Client requireClient(String name) {
        return clients.findByName(name).orElseThrow(() -> new IllegalArgumentException("No client named '" + name + "'."));
    }

    private static void requireValidName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A client name is 1 to 32 characters from a-z 0-9 _ - and starts with a letter or digit.");
        }
    }

    private String newKeyId() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder id = new StringBuilder(KEY_ID_LENGTH);
            for (int i = 0; i < KEY_ID_LENGTH; i++) {
                id.append(KEY_ID_ALPHABET.charAt(random.nextInt(KEY_ID_ALPHABET.length())));
            }
            if (!keys.existsByKeyId(id.toString())) {
                return id.toString();
            }
        }
        throw new IllegalStateException("Could not find an unused key id");
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void audit(String action, String client, String keyId) {
        audit.info("audit action={} client={} keyId={} at={}", action, client, keyId == null ? "-" : keyId, Instant.now());
    }
}
