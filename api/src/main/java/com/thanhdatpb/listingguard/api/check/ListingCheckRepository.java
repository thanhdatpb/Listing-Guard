package com.thanhdatpb.listingguard.api.check;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingCheckRepository extends JpaRepository<ListingCheck, UUID> {

    /** The stored result for this listing content and analysis version, if there is one. */
    Optional<ListingCheck> findFirstByMarketplaceCodeAndContentHashAndRulesetVersionIdAndStatus(
            String marketplaceCode, String contentHash, Long rulesetVersionId, CheckStatus status);
}
