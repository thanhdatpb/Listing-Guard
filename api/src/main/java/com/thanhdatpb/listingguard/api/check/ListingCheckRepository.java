package com.thanhdatpb.listingguard.api.check;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingCheckRepository extends JpaRepository<ListingCheck, UUID> {

    /** The stored result of this client for this listing content and analysis version, if there is one. */
    Optional<ListingCheck> findFirstByClientIdAndMarketplaceCodeAndContentHashAndRulesetVersionIdAndStatus(
            UUID clientId, String marketplaceCode, String contentHash, Long rulesetVersionId, CheckStatus status);

    /** A check of this client; one that belongs to another client is not found. */
    Optional<ListingCheck> findByIdAndClientId(UUID id, UUID clientId);
}
