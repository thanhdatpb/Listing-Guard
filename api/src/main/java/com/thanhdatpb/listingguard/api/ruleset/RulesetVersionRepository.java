package com.thanhdatpb.listingguard.api.ruleset;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RulesetVersionRepository extends JpaRepository<RulesetVersion, Long> {

    Optional<RulesetVersion> findByPackIdAndMarketplaceCodeAndVersion(String packId, String marketplaceCode,
                                                                       String version);
}
