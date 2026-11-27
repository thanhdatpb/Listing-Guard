package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccounts;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceEvents;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTemplates;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.AuditView;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredAudit;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Reason;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The entry points of the background audit for the rest of the workspace (ADR-061): put a run in the queue, audit one saved listing at once,
 * and hear what the workspace says happened. The queue and the worker do the rest.
 */
@Service
@AuditEnabled
public class WorkspaceAudit {

    private final AuditStore store;
    private final ListingAuditor auditor;
    private final AuditContexts contexts;
    private final WorkspaceAccounts accounts;
    private final WorkspaceTemplates templates;
    private final WorkspaceListings listings;
    private final org.springframework.beans.factory.ObjectProvider<AlertService> alerts;

    public WorkspaceAudit(AuditStore store, ListingAuditor auditor, AuditContexts contexts, WorkspaceAccounts accounts, WorkspaceTemplates templates,
                          WorkspaceListings listings, org.springframework.beans.factory.ObjectProvider<AlertService> alerts) {
        this.store = store;
        this.auditor = auditor;
        this.contexts = contexts;
        this.accounts = accounts;
        this.templates = templates;
        this.listings = listings;
        this.alerts = alerts;
    }

    /**
     * Puts a run for one seller account in the queue and returns its id. Empty when the account is not of the client (it is not found, never
     * "forbidden"). {@code listingIds} null means the whole account.
     */
    public Optional<UUID> request(UUID clientId, UUID accountId, Reason reason, Collection<UUID> listingIds, UUID requestedBy) {
        Optional<SellerAccount> account = accounts.find(clientId, accountId);
        if (account.isEmpty() || !account.get().enabled()) {
            return Optional.empty();
        }
        return Optional.of(store.enqueue(clientId, accountId, reason, listingIds, requestedBy));
    }

    /**
     * Audits one listing now, in the caller's thread, the way a save does (ADR-061 §3): no queue, the answer is the result. A listing that
     * is not of the client is not found. The audit of a listing whose result is current is the stored result.
     */
    public Optional<AuditView> auditNow(UUID clientId, UUID listingId) {
        Optional<Listing> listing = listings.find(clientId, listingId);
        if (listing.isEmpty()) {
            return Optional.empty();
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<SellerAccount> account = accounts.find(clientId, listing.get().accountId());
            Optional<Template> template = templates.find(clientId, listing.get().templateId());
            if (account.isEmpty() || template.isEmpty()) {
                return Optional.empty();
            }
            AuditContexts.Context context = contexts.of(clientId, account.get().marketplace().name());
            ListingAuditor.Env env = auditor.env(clientId, account.get().id(), account.get().marketplace(), template.get(), context,
                    java.util.Collections::emptyMap);
            ListingAuditor.Result result = auditor.audit(env, listing.get(), null);
            if (result.outcome() == ListingAuditor.Outcome.STALE) {
                listing = listings.find(clientId, listingId);
                if (listing.isEmpty()) {
                    return Optional.empty();
                }
                continue;
            }
            if (result.audit() == null) {
                return store.views(clientId, java.util.List.of(listingId)).values().stream().findFirst();
            }
            StoredAudit a = result.audit();
            return Optional.of(new AuditView(a.listingId(), a.state(), a.errorCount(), a.warningCount(), a.newCount(), a.auditedAt(), a.trademarkRan(),
                    a.catalogVersion()));
        }
        return Optional.empty();
    }

    /** An import wrote listings: the new and changed ones are audited, in the queue, after the import commits. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onImported(WorkspaceEvents.ListingsImported event) {
        request(event.clientId(), event.accountId(), Reason.IMPORT, event.listingIds(), event.actorId());
    }

    /** A listing was saved: it is audited at once, and a person who saved it sees the answer on the next page. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSaved(WorkspaceEvents.ListingSaved event) {
        auditNow(event.clientId(), event.listingId());
        AlertService alert = alerts.getIfAvailable();
        if (alert != null) {
            try {
                alert.send(event.clientId(), null);
            } catch (RuntimeException e) {
                // the save is done; nothing was marked as told, so the next audit's e-mail covers it
            }
        }
    }

    /** What the lists show: the latest audit of these listings of the client. */
    public Map<UUID, AuditView> views(UUID clientId, Collection<UUID> listingIds) {
        return store.views(clientId, listingIds);
    }

}
