package com.thanhdatpb.listingguard.api.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, UUID> {

    Optional<Client> findByName(String name);

    List<Client> findAllByOrderByNameAsc();
}
