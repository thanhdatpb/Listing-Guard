package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceOverrides.Override;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditHasher;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredAudit;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredFinding;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditStore;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Draft to ready to export", for a selection (ADR-062 §7). With the background audit on and {@code block-on-error} true (the default), a
 * listing cannot go while its latest audit of the text it has now says an ERROR or a HIGH trademark risk, or while it was not checked at
 * all or not checked in full (no result, a result of older text, or a part that did not run: a listing is never "ready" on a guess). An
 * ADMIN can open the block with a reason; the reason is stored with who, when, the content hash and the audit's state, and counts only
 * while the listing's content is that one. With the audit off there is no result to hold a listing back, so nothing blocks.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class ListingStatusService {

    private static final Logger log = LoggerFactory.getLogger(ListingStatusService.class);

    /** Why a listing is held back; the words of {@code workspace.status.block.<name>}. */
    public enum Block {
        ERROR, HIGH, UNCHECKED, INCOMPLETE
    }

    public enum Result {
        /** moved to ready */
        MOVED,
        /** moved after an ADMIN opened the block with a reason */
        MOVED_OPENED,
        /** held back by the check */
        BLOCKED,
        /** not a draft */
        NOT_DRAFT,
        /** the person may not change this listing */
        FORBIDDEN,
        /** changed by someone else meanwhile */
        CONFLICT,
        /** not a listing of the client */
        UNKNOWN
    }

    /** What the gate says of a listing now. {@code opened} is a valid reason of an ADMIN; {@code state} is the audit's state, NONE or STALE. */
    public record Verdict(UUID id, String sku, Set<Block> blocks, String state, Override opened) {

        /** True when the listing may go now: nothing blocks it, or a reason of an ADMIN is valid for this very content. */
        public boolean mayGo() {
            return blocks.isEmpty() || opened != null;
        }
    }

    public record Outcome(UUID id, String sku, Result result, Set<Block> blocks) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceListings listings;
    private final WorkspaceOverrides overrides;
    private final ObjectProvider<AuditStore> audits;
    private final boolean blockOnError;

    public ListingStatusService(WorkspaceAccess access, WorkspaceListings listings, WorkspaceOverrides overrides, ObjectProvider<AuditStore> audits,
                                @Value("${listing-guard.workspace.block-on-error:true}") boolean blockOnError) {
        this.access = access;
        this.listings = listings;
        this.overrides = overrides;
        this.audits = audits;
        this.blockOnError = blockOnError;
    }

    /** Whether the gate can hold anything back: the audit is on and the setting is not switched off. */
    public boolean gateActive() {
        return blockOnError && audits.getIfAvailable() != null;
    }

    /** What the gate says of each of these listings of the client, by id; a listing that is not the client's is absent. */
    public Map<UUID, Verdict> verdicts(UUID clientId, Iterable<UUID> ids) {
        List<UUID> wanted = new ArrayList<>();
        ids.forEach(wanted::add);
        Map<UUID, Listing> found = listings.findMany(clientId, wanted);
        return verdictsOf(clientId, found.values());
    }

    public Map<UUID, Verdict> verdictsOf(UUID clientId, java.util.Collection<Listing> found) {
        Map<UUID, Verdict> verdicts = new LinkedHashMap<>();
        AuditStore store = audits.getIfAvailable();
        Map<UUID, StoredAudit> stored = store == null ? Map.of() : store.audits(clientId, found.stream().map(Listing::id).toList());
        Map<UUID, Override> opened = overrides.latest(clientId, found.stream().map(Listing::id).toList());
        for (Listing l : found) {
            Set<Block> blocks = EnumSet.noneOf(Block.class);
            String state = "NONE";
            if (gateActive()) {
                StoredAudit audit = stored.get(l.id());
                if (audit == null) {
                    blocks.add(Block.UNCHECKED);
                } else if (!audit.inputHash().equals(AuditHasher.input(l.fields()))) {
                    state = "STALE";
                    blocks.add(Block.UNCHECKED);
                } else {
                    state = audit.state().name();
                    if (audit.errorCount() > 0) {
                        blocks.add(Block.ERROR);
                        if (store.findings(clientId, l.id()).stream().map(StoredFinding::level).anyMatch(level -> level == Level.HIGH)) {
                            blocks.add(Block.HIGH);
                        }
                    }
                    if (!audit.trademarkRan()) {
                        blocks.add(Block.INCOMPLETE);
                    }
                }
            }
            Override reason = opened.get(l.id());
            verdicts.put(l.id(), new Verdict(l.id(), l.sku(), blocks, state, reason != null && reason.contentHash().equals(l.contentHash()) ? reason : null));
        }
        return verdicts;
    }

    /**
     * Moves the drafts among {@code ids} to "ready to export". A listing that the gate holds back stays a draft and is reported, unless
     * {@code open} is set by an ADMIN with a reason: then the reason is stored for each such listing and it goes. Listings of other people
     * that the actor may not change, listings that are not drafts and listings changed meanwhile are reported and left alone.
     */
    @Transactional
    public List<Outcome> markReady(Actor actor, java.util.Collection<UUID> ids, boolean open, String reason) {
        String why = reason == null ? "" : reason.strip();
        if (open) {
            if (!actor.admin()) {
                throw new WorkspaceException("status.open.admin");
            }
            if (why.isEmpty() || why.codePointCount(0, why.length()) > WorkspaceOverrides.REASON_MAX) {
                throw new WorkspaceException("status.open.reason");
            }
        }
        Set<UUID> wanted = new LinkedHashSet<>(ids);
        Map<UUID, Listing> found = listings.findMany(actor.clientId(), wanted);
        Map<UUID, Verdict> verdicts = verdictsOf(actor.clientId(), found.values());
        List<Outcome> outcomes = new ArrayList<>();
        for (UUID id : wanted) {
            Listing l = found.get(id);
            if (l == null) {
                outcomes.add(new Outcome(id, "", Result.UNKNOWN, Set.of()));
                continue;
            }
            Verdict verdict = verdicts.get(id);
            Result result;
            if (!access.canEdit(actor, l.ownerAccountId())) {
                result = Result.FORBIDDEN;
            } else if (l.status() != ListingStatus.DRAFT) {
                result = Result.NOT_DRAFT;
            } else if (!verdict.mayGo() && !open) {
                result = Result.BLOCKED;
            } else if (listings.changeStatus(actor.clientId(), id, ListingStatus.READY_TO_EXPORT, l.version(), actor.accountId())) {
                result = Result.MOVED;
                if (!verdict.mayGo()) {
                    overrides.add(actor.clientId(), id, actor.accountId(), why, l.contentHash(), verdict.state(),
                            verdict.blocks().stream().map(Block::name).collect(Collectors.joining(",")));
                    result = Result.MOVED_OPENED;
                }
            } else {
                result = Result.CONFLICT;
            }
            outcomes.add(new Outcome(id, l.sku(), result, verdict.blocks()));
        }
        outcomes.sort(Comparator.comparing(Outcome::sku));
        log.info("Workspace status change by account {} of client {}: {} listings asked, {} moved", actor.accountId(), actor.clientId(),
                wanted.size(), outcomes.stream().filter(o -> o.result() == Result.MOVED || o.result() == Result.MOVED_OPENED).count());
        return outcomes;
    }

    /** Sends a ready listing back to a draft (a person's choice, never a gate). Only a listing the actor may change. */
    @Transactional
    public boolean markDraft(Actor actor, UUID id) {
        Listing l = listings.find(actor.clientId(), id).orElseThrow(() -> new WorkspaceException("listing.unknown"));
        if (!access.canEdit(actor, l.ownerAccountId())) {
            throw new WorkspaceException("forbidden");
        }
        return l.status() == ListingStatus.READY_TO_EXPORT && listings.changeStatus(actor.clientId(), id, ListingStatus.DRAFT, l.version(), actor.accountId());
    }
}
