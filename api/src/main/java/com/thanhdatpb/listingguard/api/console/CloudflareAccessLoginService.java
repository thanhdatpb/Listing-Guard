package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class CloudflareAccessLoginService {
    private final CloudflareAccessJwtVerifier verifier;
    private final ConsoleProperties properties;
    private final ConsoleAccountRepository accounts;
    private final ClientRepository clients;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public CloudflareAccessLoginService(CloudflareAccessJwtVerifier verifier, ConsoleProperties properties,
                                       ConsoleAccountRepository accounts, ClientRepository clients,
                                       JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.verifier = verifier;
        this.properties = properties;
        this.accounts = accounts;
        this.clients = clients;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Optional<ConsoleUser> authenticate(String assertion) {
        if (assertion == null || assertion.isBlank()) return Optional.empty();
        String email = verifier.verifyEmail(assertion);
        if (email == null || !isAllowed(email)) return Optional.empty();
        return Optional.ofNullable(transactions.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
                rs.next(); return null;
            }, "console-account:" + email);
            ConsoleAccount account = accounts.findByEmail(email).orElse(null);
            if (account == null) {
                if (!properties.accessAutoCreate()) return null;
                String clientName = properties.accessDefaultClientName().strip();
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
                    rs.next(); return null;
                }, "console-default-client:" + clientName);
                Client client = clients.findByName(clientName).orElseGet(() -> clients.saveAndFlush(new Client(clientName)));
                account = accounts.saveAndFlush(new ConsoleAccount(email, client.getId()));
            }
            return new ConsoleUser(account.getEmail(), account.getClientId());
        }));
    }

    public boolean isAllowed(String email) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (properties.allowedEmails().contains(normalized)) return true;
        int at = normalized.lastIndexOf('@');
        return at > 0 && properties.allowedDomains().contains(normalized.substring(at + 1));
    }
}
