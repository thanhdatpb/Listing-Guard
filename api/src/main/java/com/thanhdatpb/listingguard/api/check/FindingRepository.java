package com.thanhdatpb.listingguard.api.check;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<FindingEntity, Long> {

    List<FindingEntity> findByCheckId(UUID checkId);
}
