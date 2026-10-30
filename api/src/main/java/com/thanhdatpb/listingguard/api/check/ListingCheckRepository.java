package com.thanhdatpb.listingguard.api.check;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingCheckRepository extends JpaRepository<ListingCheck, UUID> {

    /**
     * The stored result of this client for the full cache identity (ADR-010): marketplace, pack, listing content,
     * ruleset version and analysis version, if there is one.
     */
    Optional<ListingCheck>
            findFirstByClientIdAndMarketplaceCodeAndPackIdAndContentHashAndRulesetVersionIdAndAnalysisVersionAndStatus(
                    UUID clientId, String marketplaceCode, String packId, String contentHash, Long rulesetVersionId,
                    String analysisVersion, CheckStatus status);

    /** A check of this client; one that belongs to another client is not found. */
    Optional<ListingCheck> findByIdAndClientId(UUID id, UUID clientId);
}
