package com.thanhdatpb.listingguard.api.client;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One API key of a client. The secret is never stored, only its SHA-256 ({@link ApiKeyHasher}). */
@Entity
@Table(name = "api_key")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    /** Short public identifier, safe to log and to show in listings. */
    @Column(name = "key_id", nullable = false)
    private String keyId;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    private String label;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ApiKey() {
    }

    public ApiKey(Client client, String keyId, String keyHash, String label) {
        this.client = client;
        this.keyId = keyId;
        this.keyHash = keyHash;
        this.label = label;
    }

    public UUID getId() {
        return id;
    }

    public Client getClient() {
        return client;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getLabel() {
        return label;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void revoke(Instant at) {
        this.active = false;
        this.revokedAt = at;
    }
}
