package com.thanhdatpb.listingguard.api.client;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClientRepository extends JpaRepository<Client, UUID> {

    Optional<Client> findByName(String name);

    List<Client> findAllByOrderByNameAsc();

    /**
     * The client with its row locked until the transaction ends, so that the keys of one client are counted and added one
     * request at a time (the key limit of the console). Needs a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Client c where c.id = :id")
    Optional<Client> findByIdForUpdate(@Param("id") UUID id);
}
