package com.thanhdatpb.listingguard.api.console;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsoleAccountRepository extends JpaRepository<ConsoleAccount, UUID> {

    Optional<ConsoleAccount> findByEmail(String email);
}
