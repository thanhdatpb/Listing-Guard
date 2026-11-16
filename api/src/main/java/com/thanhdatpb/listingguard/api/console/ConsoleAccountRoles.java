package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The roles of console accounts (ADR-031). The role is read from the database at each request, never kept in the session, so a
 * change by the admin job takes effect on the next page. The audit line names the account id, the old and the new role, never an
 * email.
 */
@Component
public class ConsoleAccountRoles {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAccountRoles.class);

    private final ConsoleAccountRepository accounts;
    private final ClientRepository clients;

    public ConsoleAccountRoles(ConsoleAccountRepository accounts, ClientRepository clients) {
        this.accounts = accounts;
        this.clients = clients;
    }

    /** The signed-in account, only while it still belongs to the client of its session. */
    public Optional<ConsoleAccount> account(ConsoleUser user) {
        return accounts.findByEmail(user.email()).filter(account -> account.getClientId().equals(user.clientId()));
    }

    /** Whether the signed-in account may change its client's shared settings. */
    public boolean isAdmin(ConsoleUser user) {
        return account(user).map(account -> account.getRole() == ConsoleRole.ADMIN).orElse(false);
    }

    /** The email of each account of the client, by account id, to show who changed something. */
    public Map<UUID, String> emails(UUID clientId) {
        Map<UUID, String> emails = new HashMap<>();
        accounts.findByClientIdOrderByEmail(clientId).forEach(account -> emails.put(account.getId(), account.getEmail()));
        return emails;
    }

    /**
     * Sets the role of an account (the admin job).
     *
     * @return the previous role
     * @throws IllegalArgumentException if no account has this email
     */
    @Transactional
    public ConsoleRole setRole(String email, ConsoleRole role) {
        ConsoleAccount account = accounts.findByEmail(MagicLinkService.normalizeEmail(email))
                .orElseThrow(() -> new IllegalArgumentException("No console account has this email."));
        ConsoleRole previous = account.getRole();
        if (previous != role) {
            account.setRole(role);
            accounts.saveAndFlush(account);
            log.info("Console account {} of client {}: role {} -> {} (admin job)", account.getId(), account.getClientId(),
                    previous, role);
        }
        return previous;
    }

    /**
     * The accounts of a client, by email (the admin job).
     *
     * @throws IllegalArgumentException if no client has this name
     */
    public List<ConsoleAccount> accounts(String clientName) {
        Client client = clients.findByName(clientName)
                .orElseThrow(() -> new IllegalArgumentException("No client named '" + clientName + "'."));
        return accounts.findByClientIdOrderByEmail(client.getId());
    }
}
