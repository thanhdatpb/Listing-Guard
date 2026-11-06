package com.thanhdatpb.listingguard.api.client;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /** The key with this hash and its client, loaded together because authentication needs both. */
    @Query("select k from ApiKey k join fetch k.client where k.keyHash = :hash")
    Optional<ApiKey> findByKeyHashWithClient(@Param("hash") String hash);

    Optional<ApiKey> findByKeyHash(String keyHash);

    Optional<ApiKey> findByKeyId(String keyId);

    boolean existsByKeyId(String keyId);

    List<ApiKey> findByClient_IdOrderByCreatedAtAsc(UUID clientId);

    List<ApiKey> findByClient_IdAndActiveTrueOrderByCreatedAtAsc(UUID clientId);

    long countByClient_IdAndActiveTrue(UUID clientId);

    /** The key with its row locked until the transaction ends, so two revocations of one key do not both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from ApiKey k where k.keyId = :keyId")
    Optional<ApiKey> findByKeyIdForUpdate(@Param("keyId") String keyId);

    /** Keys that can authenticate right now: the key and its client are both active. */
    @Query("select count(k) from ApiKey k where k.active = true and k.client.active = true")
    long countUsable();

    @Modifying
    @Transactional
    @Query("update ApiKey k set k.lastUsedAt = :at where k.keyId = :keyId")
    int touch(@Param("keyId") String keyId, @Param("at") Instant at);
}
