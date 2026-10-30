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

    /** The client that asked for this check; only that client can read it or be answered from it. */
    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "pack_id", nullable = false, length = 64)
    private String packId;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "marketplace_code", nullable = false)
    private String marketplaceCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CheckStatus status;

    /** The ruleset this check was produced with. */
    @Column(name = "ruleset_version_id")
    private Long rulesetVersionId;

    @Column(name = "analysis_version", nullable = false, length = 128)
    private String analysisVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ListingCheck() {
    }

    public ListingCheck(UUID clientId, String packId, String contentHash, String marketplaceCode, CheckStatus status,
                        Long rulesetVersionId, String analysisVersion) {
        this.clientId = clientId;
        this.packId = packId;
        this.contentHash = contentHash;
        this.marketplaceCode = marketplaceCode;
        this.status = status;
        this.rulesetVersionId = rulesetVersionId;
        this.analysisVersion = analysisVersion;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClientId() {
        return clientId;
    }

    public String getPackId() {
        return packId;
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

    public Long getRulesetVersionId() {
        return rulesetVersionId;
    }

    public String getAnalysisVersion() {
        return analysisVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
