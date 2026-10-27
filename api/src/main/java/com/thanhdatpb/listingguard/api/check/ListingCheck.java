package com.thanhdatpb.listingguard.api.check;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_check")
public class ListingCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "marketplace_code", nullable = false)
    private String marketplaceCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CheckStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ListingCheck() {
    }

    public ListingCheck(String contentHash, String marketplaceCode, CheckStatus status) {
        this.contentHash = contentHash;
        this.marketplaceCode = marketplaceCode;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getMarketplaceCode() {
        return marketplaceCode;
    }

    public CheckStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
