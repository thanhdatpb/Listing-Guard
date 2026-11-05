package com.thanhdatpb.listingguard.api.console;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account")
public class ConsoleAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "client_id", nullable = false, unique = true)
    private UUID clientId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ConsoleAccount() {
    }

    public ConsoleAccount(String email, UUID clientId) {
        this.email = email;
        this.clientId = clientId;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public UUID getClientId() {
        return clientId;
    }
}
