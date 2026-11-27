package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.workspace.ListingCloner;
import com.thanhdatpb.listingguard.api.workspace.ListingEditor;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService.Block;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService.Result;
import com.thanhdatpb.listingguard.api.workspace.SkuPlan;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceOverrides;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The editor with the background audit on (ADR-062 with ADR-061): a save says {@code ListingSaved} and the audit hears it only after the
 * commit; the clone reaches the audit through the event the import uses; "ready to export" is held back by an error, a HIGH trademark risk
 * or a listing that was not checked in full; an administrator opens the block with a reason that stops counting when the content changes.
 * Every client, mark and listing is synthetic.
 */
class WorkspaceEditorAuditIntegrationTest extends AuditIntegrationBase {

    @Autowired
    ListingEditor editor;
    @Autowired
    ListingCloner cloner;
    @Autowired
    ListingStatusService statusService;
    @Autowired
    WorkspaceOverrides overrides;
    @Autowired
    WorkspaceAccess access;
    @Autowired
    TransactionTemplate transaction;
    @Autowired
    ObjectProvider<AuditStore> auditStores;

    private Actor actor(ConsoleActor who) {
        return who.actor();
    }

    private record ConsoleActor(Actor actor) {
    }

    private Actor admin(Team team) {
        return access.actor(team.admin()).orElseThrow();
    }

    private Actor member(Team team) {
        return access.actor(team.member()).orElseThrow();
    }

    /** The columns of a valid Etsy listing, as a form posts them. */
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

    private Map<String, String> with(Listing listing, String key, String value) {
        Map<String, String> posted = new LinkedHashMap<>(listing.data());
        posted.put(key, value);
        return posted;
    }

    private Listing reload(Team team, Listing listing) {
        return listings.find(team.client().getId(), listing.id()).orElseThrow();
    }

    // ------------------------------------------------------------------ ListingSaved

    @Test
    void aSaveIsAuditedAfterTheCommitWithoutAnyoneCallingTheAudit() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop A");
        serve("a", "Qualmark");

        Listing created = editor.create(member(team), shop.id(), form("A-1", "Plain ceramic mug"));

        var first = store.audit(team.client().getId(), created.id()).orElseThrow();
        assertThat(first.listingVersion()).isEqualTo(created.version());
        assertThat(first.state()).isNotEqualTo(AuditTypes.State.NEW_RISK);

        Listing saved = reload(team, created);
        editor.save(member(team), saved.id(), saved.version(), with(saved, "title", "Qualmark ceramic mug"));

        Listing after = reload(team, created);
        var audit = store.audit(team.client().getId(), created.id()).orElseThrow();
        // the audit read the text that was committed, at the version the save wrote
        assertThat(audit.listingVersion()).isEqualTo(after.version());
        assertThat(audit.inputHash()).isNotEqualTo(first.inputHash());
        assertThat(findingsOf(after)).anyMatch(f -> "CATALOG".equals(f.get("source")) && "title".equals(f.get("field")));
        assertThat(stateOf(after)).isEqualTo("ERROR");
    }

    @Test
    void aSaveThatRollsBackSaysNothingAndTheAuditNeverSeesIt() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop B");
        serve("b", "Qualmark");
        Listing created = editor.create(member(team), shop.id(), form("B-1", "Plain ceramic mug"));
        Listing saved = reload(team, created);
        var audited = store.audit(team.client().getId(), created.id()).orElseThrow();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            editor.save(member(team), saved.id(), saved.version(), with(saved, "title", "Qualmark rolled back"));
            throw new IllegalStateException("the surrounding work failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(reload(team, created).version()).isEqualTo(saved.version());
        assertThat(reload(team, created).title()).isEqualTo("Plain ceramic mug");
        assertThat(store.audit(team.client().getId(), created.id()).orElseThrow().inputHash()).isEqualTo(audited.inputHash());
    }

    @Test
    void aSaveThatChangesNothingCostsNoAudit() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop C");
        serve("c", "Qualmark");
        Listing created = editor.create(member(team), shop.id(), form("C-1", "Plain ceramic mug"));
        Listing saved = reload(team, created);
        long checks = jdbc.queryForObject("SELECT COUNT(*) FROM listing_check WHERE client_id = ?", Long.class, team.client().getId());

        assertThat(editor.save(member(team), saved.id(), saved.version(), new LinkedHashMap<>(saved.data())).changed()).isFalse();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_check WHERE client_id = ?", Long.class, team.client().getId())).isEqualTo(checks);
    }

    @Test
    void aCloneReachesTheAuditThroughTheEventOfTheImportAndTheQueueAuditsTheNewListings() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop D");
        serve("d", "Qualmark");
        Listing source = editor.create(member(team), shop.id(), form("D-1", "Plain ceramic mug"));

        List<Listing> clones = cloner.clone(member(team), new ListingCloner.Request(shop.id(), List.of(source.id()), false,
                new SkuPlan(SkuPlan.Mode.PATTERN, "", "-C", "", "", null, 0, Map.of()), Map.of(), false));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_audit_run WHERE client_id = ? AND reason = 'IMPORT' AND status = 'QUEUED'",
                Long.class, team.client().getId())).isEqualTo(1);
        audit();
        assertThat(stateOf(clones.get(0))).isNotEqualTo("NONE");
        assertThat(store.audit(team.client().getId(), clones.get(0).id())).isPresent();
    }

    // ------------------------------------------------------------------ ready to export

    private Listing risky(Team team, SellerAccount shop, String sku) {
        return editor.create(member(team), shop.id(), form(sku, "Qualmark ceramic mug " + sku));
    }

    @Test
    void aListingWithAnErrorOrAHighRiskStaysADraftAndAnAdministratorOpensTheBlockWithAReason() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop E");
        serve("e", "Qualmark");
        Listing bad = risky(team, shop, "E-1");
        Listing good = editor.create(member(team), shop.id(), form("E-2", "Plain ceramic mug"));
        assertThat(statusService.gateActive()).isTrue();
        assertThat(stateOf(bad)).isEqualTo("ERROR");
        assertThat(stateOf(good)).isIn("CLEAN", "WARNING");

        List<ListingStatusService.Outcome> outcomes = statusService.markReady(member(team), List.of(bad.id(), good.id()), false, null);

        assertThat(outcomes).filteredOn(o -> o.id().equals(bad.id())).singleElement().satisfies(o -> {
            assertThat(o.result()).isEqualTo(Result.BLOCKED);
            assertThat(o.blocks()).contains(Block.ERROR, Block.HIGH);
        });
        assertThat(outcomes).filteredOn(o -> o.id().equals(good.id())).singleElement().satisfies(o -> assertThat(o.result()).isEqualTo(Result.MOVED));
        assertThat(reload(team, bad).status()).isEqualTo(ListingStatus.DRAFT);
        assertThat(reload(team, good).status()).isEqualTo(ListingStatus.READY_TO_EXPORT);

        // a member cannot open it, and an administrator needs a reason
        assertThatThrownBy(() -> statusService.markReady(member(team), List.of(bad.id()), true, "I know")).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("status.open.admin"));
        assertThatThrownBy(() -> statusService.markReady(admin(team), List.of(bad.id()), true, "   ")).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("status.open.reason"));
        assertThat(reload(team, bad).status()).isEqualTo(ListingStatus.DRAFT);

        Listing before = reload(team, bad);
        var opened = statusService.markReady(admin(team), List.of(bad.id()), true, "The mark is the seller's own brand");

        assertThat(opened).singleElement().satisfies(o -> assertThat(o.result()).isEqualTo(Result.MOVED_OPENED));
        assertThat(reload(team, bad).status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
        var row = overrides.latest(team.client().getId(), List.of(bad.id())).get(bad.id());
        assertThat(row.reason()).isEqualTo("The mark is the seller's own brand");
        assertThat(row.openedBy()).isEqualTo(team.adminAccount().getId());
        assertThat(row.contentHash()).isEqualTo(before.contentHash());
        assertThat(row.auditState()).isEqualTo("ERROR");
        assertThat(row.blockReasons()).isEqualTo("ERROR,HIGH");
        assertThat(row.openedAt()).isNotNull();
    }

    @Test
    void theReasonStopsCountingAsSoonAsTheContentChangesAndTheListingIsBlockedAgain() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop F");
        serve("f", "Qualmark");
        Listing bad = risky(team, shop, "F-1");
        statusService.markReady(admin(team), List.of(bad.id()), true, "Own brand");
        assertThat(statusService.verdicts(team.client().getId(), List.of(bad.id())).get(bad.id()).opened()).isNotNull();
        assertThat(statusService.verdicts(team.client().getId(), List.of(bad.id())).get(bad.id()).mayGo()).isTrue();

        // the content changes: the listing goes back to a draft, and the reason belongs to the content it was given for
        Listing ready = reload(team, bad);
        ListingEditor.Saved saved = editor.save(member(team), ready.id(), ready.version(), with(ready, "title", "Qualmark ceramic mug, edited"));
        assertThat(saved.readyReverted()).isTrue();
        Listing edited = reload(team, bad);
        assertThat(edited.status()).isEqualTo(ListingStatus.DRAFT);
        var verdict = statusService.verdicts(team.client().getId(), List.of(bad.id())).get(bad.id());
        assertThat(verdict.opened()).isNull();
        assertThat(verdict.mayGo()).isFalse();
        assertThat(statusService.markReady(member(team), List.of(bad.id()), false, null)).singleElement()
                .satisfies(o -> assertThat(o.result()).isEqualTo(Result.BLOCKED));

        // the same content again makes the old reason valid again: it was given for exactly that text
        editor.save(member(team), edited.id(), edited.version(), with(edited, "title", "Qualmark ceramic mug F-1"));
        assertThat(statusService.verdicts(team.client().getId(), List.of(bad.id())).get(bad.id()).opened()).isNotNull();
    }

    @Test
    void aListingThatWasNeverCheckedOrWasNotCheckedInFullIsHeldBackToo() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop G");
        // no catalog is served and the client has no watchlist: the trademark part cannot run
        Listing unchecked = insert(team, shop, etsyTemplate(team, shop), "G-1", fields("Plain ceramic mug", "A plain description."), team.memberAccount().getId());
        Listing incomplete = editor.create(member(team), shop.id(), form("G-2", "Plain ceramic mug two"));
        assertThat(stateOf(incomplete)).isEqualTo("INCOMPLETE");

        var verdicts = statusService.verdicts(team.client().getId(), List.of(unchecked.id(), incomplete.id()));

        assertThat(verdicts.get(unchecked.id()).blocks()).containsExactly(Block.UNCHECKED);
        assertThat(verdicts.get(unchecked.id()).state()).isEqualTo("NONE");
        assertThat(verdicts.get(incomplete.id()).blocks()).containsExactly(Block.INCOMPLETE);
        // the text of an audit that is older than the listing is not a check of this listing
        jdbc.update("UPDATE workspace_listing SET fields = jsonb_set(fields, '{title}', '\"Another title\"') WHERE id = ?", incomplete.id());
        var stale = statusService.verdicts(team.client().getId(), List.of(incomplete.id())).get(incomplete.id());
        assertThat(stale.state()).isEqualTo("STALE");
        assertThat(stale.blocks()).contains(Block.UNCHECKED);
    }

    @Test
    void theSettingThatSwitchesTheBlockOffLetsEveryDraftGo() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop H");
        serve("h", "Qualmark");
        Listing bad = risky(team, shop, "H-1");
        ListingStatusService open = new ListingStatusService(access, listings, overrides, auditStores, false);

        assertThat(open.gateActive()).isFalse();
        assertThat(open.markReady(member(team), List.of(bad.id()), false, null)).singleElement().satisfies(o -> assertThat(o.result()).isEqualTo(Result.MOVED));
        assertThat(overrides.latest(team.client().getId(), List.of(bad.id()))).isEmpty();
    }

    @Test
    void anotherClientNeverSeesTheReasonsOrTheListings() {
        Team team = team();
        Team stranger = team();
        SellerAccount shop = etsyShop(team, "Shop I");
        serve("i", "Qualmark");
        Listing bad = risky(team, shop, "I-1");
        statusService.markReady(admin(team), List.of(bad.id()), true, "Own brand");

        assertThat(overrides.latest(stranger.client().getId(), List.of(bad.id()))).isEmpty();
        assertThat(statusService.verdicts(stranger.client().getId(), List.of(bad.id()))).isEmpty();
        assertThat(statusService.markReady(admin(stranger), List.of(bad.id()), true, "x")).singleElement().satisfies(o -> assertThat(o.result()).isEqualTo(Result.UNKNOWN));
    }
}
