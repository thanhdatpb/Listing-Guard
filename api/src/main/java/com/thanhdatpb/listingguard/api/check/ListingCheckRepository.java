package com.thanhdatpb.listingguard.api.check;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingCheckRepository extends JpaRepository<ListingCheck, UUID> {
}
