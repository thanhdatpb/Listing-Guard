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
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.TrademarkReason;
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
 * "Draft to ready to export", for a selection (ADR-062 §7, adjusted by ADR-063 §1), and the one gate that the export also asks. With
 * {@code block-on-error} true (the default), a listing cannot go while its latest audit of the text it has now says an ERROR or a HIGH
 * trademark risk, while it was not checked (no result, or a result of older text), or while its trademark part did not run for a cause that
 * can pass or be mended (a catalog being loaded, a rule that failed). A trademark part that did not run because the client has no trademark
 * set-up (no catalog served and no watchlist, or a plan without the pack) does not hold the listing back, and the listing is said to be
 * unchecked for trademark wherever it is shown. With the background audit off no listing has a result, so every listing is unchecked and
 * held back; {@code block-on-error=false} switches the gate off. An ADMIN can open the block with a reason; the reason is stored with who,
 * when, the content hash and the audit's state, and counts only while the listing's content is that one.
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

    /**
     * What the gate says of a listing now. {@code opened} is a valid reason of an ADMIN; {@code state} is the audit's state, NONE or STALE.
     * {@code trademarkChecked} is true when the audit of this very content ran its trademark part in full, whether or not the gate is on;
     * {@code trademarkReason} is why it did not, when there is an audit of this content (null otherwise).
     */
    public record Verdict(UUID id, String sku, Set<Block> blocks, String state, Override opened, boolean trademarkChecked,
                          TrademarkReason trademarkReason) {

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

    /** Whether the gate can hold anything back: {@code block-on-error} is not switched off. */
    public boolean gateActive() {
        return blockOnError;
    }

    /** Whether the background audit is on, so a listing can have a result at all; with it off the gate holds every listing back as unchecked. */
    public boolean auditActive() {
        return audits.getIfAvailable() != null;
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
            StoredAudit audit = stored.get(l.id());
            boolean current = audit != null && audit.inputHash().equals(AuditHasher.input(l.fields()));
            String state = audit == null ? "NONE" : current ? audit.state().name() : "STALE";
            if (gateActive()) {
                if (!current) {
                    // no result, a result of older text, or no audit at all: a listing is never "ready" on a guess (ADR-061 §4)
                    blocks.add(Block.UNCHECKED);
                } else {
                    if (audit.errorCount() > 0) {
                        blocks.add(Block.ERROR);
                        if (store.findings(clientId, l.id()).stream().map(StoredFinding::level).anyMatch(level -> level == Level.HIGH)) {
                            blocks.add(Block.HIGH);
                        }
                    }
                    // a result stored before V60 has no reason: it holds back until the audit is made again (ADR-063 §1)
                    if (audit.trademarkReason() == null || audit.trademarkReason().holdsBack()) {
                        blocks.add(Block.INCOMPLETE);
                    }
                }
            }
            Override reason = opened.get(l.id());
            verdicts.put(l.id(), new Verdict(l.id(), l.sku(), blocks, state,
                    reason != null && reason.contentHash().equals(l.contentHash()) ? reason : null,
                    current && audit.trademarkRan(), current ? audit.trademarkReason() : null));
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

    /**
     * Sends a listing that is ready, exported or live back to a draft (a person's choice, never a gate): the way to export a listing again or
     * to change one that is live (ADR-063 §7). Only a listing the actor may change; the change is a STATUS entry of its history.
     */
    @Transactional
    public boolean markDraft(Actor actor, UUID id) {
        Listing l = listings.find(actor.clientId(), id).orElseThrow(() -> new WorkspaceException("listing.unknown"));
        if (!access.canEdit(actor, l.ownerAccountId())) {
            throw new WorkspaceException("forbidden");
        }
        return l.status() != ListingStatus.DRAFT && listings.changeStatus(actor.clientId(), id, ListingStatus.DRAFT, l.version(), actor.accountId());
    }
}
