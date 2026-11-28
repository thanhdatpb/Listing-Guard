package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.workspace.ListingEditor;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService.Block;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService.Result;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceOverrides;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.TrademarkReason;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The gate of ADR-063 §1 on a real database, with the real audit worker, a real catalog and a real watchlist: the four combinations of a served
 * catalog and a watchlist, a catalog that cannot be served now and then can, and an audit stored before V60. Each case checks what the audit
 * stored (the reason), what the gate says of it, and what became of the listing in the database after the move to "ready to export". The
 * combinations of the setting and of the reasons of an ADMIN are in {@code ListingStatusGateTest}; the audit off is in
 * {@code WorkspaceEditorIntegrationTest}. Every client, mark and listing is synthetic.
 */
class WorkspaceGateIntegrationTest extends AuditIntegrationBase {

    @Autowired
    ListingEditor editor;
    @Autowired
    ListingStatusService statusService;
    @Autowired
    WorkspaceOverrides overrides;
    @Autowired
    WorkspaceAccess access;

    private Actor admin(Team team) {
        return access.actor(team.admin()).orElseThrow();
    }

    private Actor member(Team team) {
        return access.actor(team.member()).orElseThrow();
    }

    private Map<String, String> form(String sku, String title) {
        Map<String, String> posted = new LinkedHashMap<>();
        posted.put("sku", sku);
        posted.put("title", title);
        posted.put("description", "A plain description.");
        posted.put("tags", "mug, ceramic, gift");
        posted.put("price", "19.99");
        posted.put("quantity", "5");
        posted.put("category", "Mugs");
        posted.put("who_made", "i_did");
        posted.put("when_made", "made_to_order");
        return posted;
    }

    private Listing reload(Team team, Listing l) {
        return listings.find(team.client().getId(), l.id()).orElseThrow();
    }

    private String reasonOf(Listing l) {
        return jdbc.queryForObject("SELECT trademark_reason FROM workspace_listing_audit WHERE listing_id = ?", String.class, l.id());
    }

    private ListingStatusService.Verdict verdict(Team team, Listing l) {
        return statusService.verdicts(team.client().getId(), List.of(l.id())).get(l.id());
    }

    // ------------------------------------------------------------------ the four combinations

    @Test
    void aServedCatalogWithOrWithoutAWatchlistRunsTheTrademarkPartAndLetsAPlainListingGo() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop A");
        serve("a", "Qualmark");
        Listing alone = editor.create(member(team), shop.id(), form("A-1", "Plain ceramic mug"));
        assertThat(reasonOf(alone)).isEqualTo("RAN");
        assertThat(verdict(team, alone).blocks()).isEmpty();
        assertThat(verdict(team, alone).trademarkChecked()).isTrue();

        watch(team, "Brimwick Otters");
        audit();
        Listing both = editor.create(member(team), shop.id(), form("A-2", "Another plain mug"));
        assertThat(reasonOf(both)).isEqualTo("RAN");
        assertThat(reasonOf(reload(team, alone))).isEqualTo("RAN");
        assertThat(statusService.markReady(member(team), List.of(alone.id(), both.id()), false, null)).extracting(ListingStatusService.Outcome::result)
                .containsOnly(Result.MOVED);
        assertThat(reload(team, alone).status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
        assertThat(reload(team, both).status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
    }

    @Test
    void aClientWithNoCatalogAndNoWatchlistIsNotHeldBackAndTheListingIsSaidToBeUncheckedForTrademark() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop B");
        Listing l = editor.create(member(team), shop.id(), form("B-1", "Plain ceramic mug"));

        assertThat(reasonOf(l)).isEqualTo("NOT_CONFIGURED");
        assertThat(stateOf(l)).isEqualTo("INCOMPLETE");
        var verdict = verdict(team, l);
        assertThat(verdict.blocks()).isEmpty();
        assertThat(verdict.trademarkChecked()).isFalse();
        assertThat(verdict.trademarkReason()).isEqualTo(TrademarkReason.NOT_CONFIGURED);

        assertThat(statusService.markReady(member(team), List.of(l.id()), false, null)).singleElement()
                .satisfies(o -> assertThat(o.result()).isEqualTo(Result.MOVED));
        assertThat(reload(team, l).status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
        assertThat(overrides.latest(team.client().getId(), List.of(l.id()))).isEmpty();
    }

    @Test
    void aWatchlistWithoutACatalogOnlyLooksAtTheWatchlistAndDoesNotHoldTheListingBackButSaysSo() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop C");
        watch(team, "Brimwick Otters");
        Listing l = editor.create(member(team), shop.id(), form("C-1", "Plain ceramic mug"));

        assertThat(reasonOf(l)).isEqualTo("WATCHLIST_ONLY");
        var verdict = verdict(team, l);
        assertThat(verdict.blocks()).isEmpty();
        assertThat(verdict.trademarkChecked()).isFalse();
        assertThat(verdict.catalogUnchecked()).isTrue();
        assertThat(statusService.markReady(member(team), List.of(l.id()), false, null)).singleElement()
                .satisfies(o -> assertThat(o.result()).isEqualTo(Result.MOVED));
        assertThat(reload(team, l).status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
        // nobody opened anything: there was nothing to open
        assertThat(overrides.latest(team.client().getId(), List.of(l.id()))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_status_override WHERE listing_id = ?", Long.class, l.id())).isZero();

        // a changed text is a draft again, audited again with the same cause, and goes again without a reason
        Listing ready = reload(team, l);
        Map<String, String> edited = new LinkedHashMap<>(ready.data());
        edited.put("title", "Plain ceramic mug, edited");
        editor.save(member(team), ready.id(), ready.version(), edited);
        assertThat(reload(team, l).status()).isEqualTo(ListingStatus.DRAFT);
        assertThat(reasonOf(reload(team, l))).isEqualTo("WATCHLIST_ONLY");
        assertThat(verdict(team, reload(team, l)).blocks()).isEmpty();
    }

    @Test
    void aHitOfTheWatchlistWithoutACatalogStillHoldsTheListingBack() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop C2");
        watch(team, "Brimwick Otters");
        Listing l = editor.create(member(team), shop.id(), form("C-2", "Brimwick Otters ceramic mug"));

        assertThat(reasonOf(l)).isEqualTo("WATCHLIST_ONLY");
        assertThat(verdict(team, l).blocks()).contains(Block.ERROR, Block.HIGH);
        assertThat(statusService.markReady(member(team), List.of(l.id()), false, null)).singleElement()
                .satisfies(o -> assertThat(o.result()).isEqualTo(Result.BLOCKED));
        assertThat(reload(team, l).status()).isEqualTo(ListingStatus.DRAFT);
    }

    // ------------------------------------------------------------------ a cause that passes

    @Test
    void aCatalogThatCannotBeServedNowHoldsTheListingBackAndOnceItCanTheNextAuditLetsItGo() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop D");
        serve("d1", "Qualmark");
        // the activated version is gone: the runtime refuses to serve (a state that may pass, not a client that set nothing up)
        jdbc.update("UPDATE trademark_catalog_activation SET catalog_version_id = NULL");
        assertThat(runtime.evaluate().state()).isEqualTo(com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime.State.REFUSED);
        Listing l = editor.create(member(team), shop.id(), form("D-1", "Plain ceramic mug"));

        assertThat(reasonOf(l)).isEqualTo("UNAVAILABLE");
        assertThat(verdict(team, l).blocks()).containsExactly(Block.INCOMPLETE);
        assertThat(statusService.markReady(member(team), List.of(l.id()), false, null)).singleElement()
                .satisfies(o -> assertThat(o.result()).isEqualTo(Result.BLOCKED));

        serve("d2", "Qualmark");
        audit();

        assertThat(reasonOf(l)).isEqualTo("RAN");
        assertThat(verdict(team, l).blocks()).isEmpty();
        assertThat(statusService.markReady(member(team), List.of(l.id()), false, null)).singleElement()
                .satisfies(o -> assertThat(o.result()).isEqualTo(Result.MOVED));
        assertThat(reload(team, l).status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
    }

    @Test
    void anAuditStoredBeforeTheReasonExistedHoldsTheListingBackUntilItIsAuditedAgainOnce() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop E");
        Listing l = editor.create(member(team), shop.id(), form("E-1", "Plain ceramic mug"));
        assertThat(reasonOf(l)).isEqualTo("NOT_CONFIGURED");
        jdbc.update("UPDATE workspace_listing_audit SET trademark_reason = NULL WHERE listing_id = ?", l.id());
        assertThat(verdict(team, l).blocks()).containsExactly(Block.INCOMPLETE);

        store.enqueue(team.client().getId(), shop.id(), AuditTypes.Reason.MANUAL, null, null);
        worker.drain();

        // nothing in the text or the context changed, and the listing was audited again because the reason was missing
        assertThat(reasonOf(l)).isEqualTo("NOT_CONFIGURED");
        assertThat(verdict(team, l).blocks()).isEmpty();
        // once the reason is there, a run that finds nothing new leaves the audit alone
        var audited = store.audit(team.client().getId(), l.id()).orElseThrow();
        store.enqueue(team.client().getId(), shop.id(), AuditTypes.Reason.MANUAL, null, null);
        worker.drain();
        assertThat(store.audit(team.client().getId(), l.id()).orElseThrow().auditedAt()).isEqualTo(audited.auditedAt());
    }
}
