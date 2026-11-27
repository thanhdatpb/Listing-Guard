package com.thanhdatpb.listingguard.api.console.workspace;

import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditEnabled;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditStore;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The card "Listings with a new risk" of the home pages (ADR-061): the number of listings of the signed-in person's client that have a new
 * risk nobody marked as seen. An ADMIN's card counts the whole client; a MEMBER's counts the listings they are responsible for. It reads only
 * the client of the account and exists only while the audit is on.
 */
@Component
@AuditEnabled
public class AuditHomeCard {

    /** @param mine true for a MEMBER, whose count is of their own listings */
    public record Card(long count, boolean mine) {
    }

    private final WorkspaceAccess access;
    private final AuditStore store;

    public AuditHomeCard(WorkspaceAccess access, AuditStore store) {
        this.access = access;
        this.store = store;
    }

    public Optional<Card> of(ConsoleUser user) {
        return access.actor(user).map(actor -> new Card(store.counts(actor.clientId(), actor.admin() ? null : actor.accountId()).newRisk(), !actor.admin()));
    }
}
