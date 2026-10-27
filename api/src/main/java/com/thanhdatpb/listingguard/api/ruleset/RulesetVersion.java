package com.thanhdatpb.listingguard.api.ruleset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** A ruleset registered in the database: its version and the hash of the content it had. */
@Entity
@Table(name = "ruleset_version")
public class RulesetVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "marketplace_code", nullable = false)
    private String marketplaceCode;

    @Column(nullable = false)
    private String version;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "checked_at", nullable = false)
    private LocalDate checkedAt;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RulesetVersion() {
    }

    public RulesetVersion(String marketplaceCode, String version, String contentHash, LocalDate checkedAt, String source) {
        this.marketplaceCode = marketplaceCode;
        this.version = version;
        this.contentHash = contentHash;
        this.checkedAt = checkedAt;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public String getMarketplaceCode() {
        return marketplaceCode;
    }

    public String getVersion() {
        return version;
    }

    public String getContentHash() {
        return contentHash;
    }

    public LocalDate getCheckedAt() {
        return checkedAt;
    }

    public String getSource() {
        return source;
    }
}
