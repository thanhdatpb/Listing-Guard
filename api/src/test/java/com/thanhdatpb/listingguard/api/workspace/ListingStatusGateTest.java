package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService.Block;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService.Verdict;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceOverrides.Override;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingSource;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditHasher;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredAudit;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredFinding;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditStore;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Level;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Source;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.State;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.TrademarkReason;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The gate of ADR-062 §7 as ADR-063 §1 adjusts it, one case for each combination that the ADR names: the audit on or off, {@code block-on-error}
 * true or false, the cause of a trademark part that did not run (nothing set up, no plan pack, a catalog that may pass, a failed rule, only a
 * watchlist, a result from before V60), the state of the audit's result (none, of older text, of this text) and a reason of an ADMIN that is
 * still valid or no longer is. The gate is read from stored results only, so no database is needed; the end-to-end cases with a real catalog and a
 * real watchlist are in {@code WorkspaceGateIntegrationTest}.
 */
class ListingStatusGateTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What a case stores of the audit: its text relation to the listing, its state, error count and trademark reason. */
    private enum Audit {
        NONE, OLDER_TEXT, THIS_TEXT
    }

    private static JsonNode fields(String title) {
        return MAPPER.createObjectNode().put("title", title);
    }

    private static Listing listing(UUID id) {
        return new Listing(id, CLIENT, UUID.randomUUID(), UUID.randomUUID(), "SKU-" + id.toString().substring(0, 4), null, ListingRole.STANDALONE,
                "Plain mug", Map.of("title", "Plain mug"), fields("Plain mug"), "h".repeat(64), UUID.randomUUID(), ListingStatus.DRAFT,
                ListingSource.IMPORT, null, 0, Instant.now(), Instant.now(), null);
    }

    private static StoredAudit stored(Listing l, Audit audit, int errors, boolean ran, TrademarkReason reason) {
        String hash = audit == Audit.THIS_TEXT ? AuditHasher.input(l.fields()) : AuditHasher.input(fields("Another title"));
        State state = errors > 0 ? State.ERROR : ran ? State.CLEAN : State.INCOMPLETE;
        return new StoredAudit(l.id(), CLIENT, l.accountId(), null, state, hash, "a".repeat(64), null, null, ran, l.version(), null, null, errors, 0, 0,
                Instant.now(), null, null, reason);
    }

    private record Setup(boolean auditOn, boolean blockOnError, Audit audit, int errors, boolean high, boolean ran, TrademarkReason reason,
                         Boolean validReason) {
    }

    private static Verdict verdict(Setup s) {
        Listing l = listing(UUID.randomUUID());
        AuditStore store = mock(AuditStore.class);
        if (s.audit() != Audit.NONE) {
            when(store.audits(eq(CLIENT), any())).thenReturn(Map.of(l.id(), stored(l, s.audit(), s.errors(), s.ran(), s.reason())));
        } else {
            when(store.audits(eq(CLIENT), any())).thenReturn(Map.of());
        }
        when(store.findings(CLIENT, l.id())).thenReturn(s.high()
                ? List.of(new StoredFinding(1, 0, "trademark-risk", "mark", Level.HIGH, Source.CATALOG, "title", 0, 5, "x", null, null, null, null, "m", null, false))
                : List.of());
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(s.auditOn() ? store : null);
        WorkspaceOverrides overrides = mock(WorkspaceOverrides.class);
        if (s.validReason() == null) {
            when(overrides.latest(eq(CLIENT), any())).thenReturn(Map.of());
        } else {
            String hash = s.validReason() ? l.contentHash() : "x".repeat(64);
            when(overrides.latest(eq(CLIENT), any())).thenReturn(Map.of(l.id(),
                    new Override(1, l.id(), UUID.randomUUID(), "Own brand", Instant.now(), hash, "ERROR", "ERROR")));
        }
        ListingStatusService service = new ListingStatusService(mock(WorkspaceAccess.class), mock(WorkspaceListings.class), overrides, provider,
                s.blockOnError());
        return service.verdictsOf(CLIENT, List.of(l)).get(l.id());
    }

    private static Setup on(Audit audit, int errors, boolean ran, TrademarkReason reason) {
        return new Setup(true, true, audit, errors, false, ran, reason, null);
    }

    // ------------------------------------------------------------------ the cause of a trademark part that did not run

    @Test
    void aTrademarkPartThatRanInFullHoldsNothingBack() {
        Verdict v = verdict(on(Audit.THIS_TEXT, 0, true, TrademarkReason.RAN));
        assertThat(v.blocks()).isEmpty();
        assertThat(v.mayGo()).isTrue();
        assertThat(v.trademarkChecked()).isTrue();
    }

    @Test
    void aClientWithNoTrademarkSetUpIsNotHeldBackAndTheListingIsSaidToBeUnchecked() {
        for (TrademarkReason reason : List.of(TrademarkReason.NOT_CONFIGURED, TrademarkReason.NO_PLAN)) {
            Verdict v = verdict(on(Audit.THIS_TEXT, 0, false, reason));
            assertThat(v.blocks()).as(reason.name()).isEmpty();
            assertThat(v.mayGo()).as(reason.name()).isTrue();
            assertThat(v.trademarkChecked()).as(reason.name()).isFalse();
            assertThat(v.trademarkReason()).isEqualTo(reason);
        }
    }

    @Test
    void aCauseThatCanPassOrBeMendedHoldsTheListingBack() {
        for (TrademarkReason reason : List.of(TrademarkReason.UNAVAILABLE, TrademarkReason.INCOMPLETE, TrademarkReason.WATCHLIST_ONLY)) {
            Verdict v = verdict(on(Audit.THIS_TEXT, 0, false, reason));
            assertThat(v.blocks()).as(reason.name()).containsExactly(Block.INCOMPLETE);
            assertThat(v.mayGo()).as(reason.name()).isFalse();
            assertThat(v.trademarkChecked()).as(reason.name()).isFalse();
        }
    }

    @Test
    void aResultFromBeforeV60HasNoReasonAndHoldsTheListingBackUntilItIsAuditedAgain() {
        Verdict v = verdict(on(Audit.THIS_TEXT, 0, false, null));
        assertThat(v.blocks()).containsExactly(Block.INCOMPLETE);
        // a result from before V60 that did run its trademark part is RAN (the migration fills it); built here by the public constructor
        Listing l = listing(UUID.randomUUID());
        StoredAudit old = new StoredAudit(l.id(), CLIENT, l.accountId(), null, State.CLEAN, AuditHasher.input(l.fields()), "a".repeat(64), null, null,
                true, 0, null, null, 0, 0, 0, Instant.now(), null, null);
        assertThat(old.trademarkReason()).isEqualTo(TrademarkReason.RAN);
    }

    @Test
    void everyReasonHasOneMeaningForTheGate() {
        assertThat(EnumSet.allOf(TrademarkReason.class)).allSatisfy(reason -> assertThat(reason.notSetUp() && reason.holdsBack()).isFalse());
        assertThat(TrademarkReason.RAN.notSetUp()).isFalse();
        assertThat(TrademarkReason.RAN.holdsBack()).isFalse();
        assertThat(EnumSet.allOf(TrademarkReason.class).stream().filter(TrademarkReason::notSetUp)).containsExactlyInAnyOrder(
                TrademarkReason.NOT_CONFIGURED, TrademarkReason.NO_PLAN);
    }

    // ------------------------------------------------------------------ errors and the state of the result

    @Test
    void anErrorHoldsTheListingBackAndAHighRiskIsNamedApart() {
        Verdict error = verdict(on(Audit.THIS_TEXT, 2, true, TrademarkReason.RAN));
        assertThat(error.blocks()).containsExactly(Block.ERROR);
        Verdict high = verdict(new Setup(true, true, Audit.THIS_TEXT, 1, true, true, TrademarkReason.RAN, null));
        assertThat(high.blocks()).containsExactlyInAnyOrder(Block.ERROR, Block.HIGH);
    }

    @Test
    void anErrorHoldsBackEvenForAClientWithNoTrademarkSetUp() {
        Verdict v = verdict(on(Audit.THIS_TEXT, 1, false, TrademarkReason.NOT_CONFIGURED));
        assertThat(v.blocks()).containsExactly(Block.ERROR);
        assertThat(v.trademarkChecked()).isFalse();
    }

    @Test
    void noResultAndAResultOfOlderTextAreUncheckedWhateverTheReasonSays() {
        Verdict none = verdict(on(Audit.NONE, 0, true, TrademarkReason.RAN));
        assertThat(none.blocks()).containsExactly(Block.UNCHECKED);
        assertThat(none.state()).isEqualTo("NONE");
        Verdict older = verdict(on(Audit.OLDER_TEXT, 0, true, TrademarkReason.RAN));
        assertThat(older.blocks()).containsExactly(Block.UNCHECKED);
        assertThat(older.state()).isEqualTo("STALE");
        assertThat(older.trademarkChecked()).isFalse();
        assertThat(older.trademarkReason()).isNull();
        Verdict olderNoSetUp = verdict(on(Audit.OLDER_TEXT, 0, false, TrademarkReason.NOT_CONFIGURED));
        assertThat(olderNoSetUp.blocks()).containsExactly(Block.UNCHECKED);
    }

    // ------------------------------------------------------------------ the audit off, the setting off

    @Test
    void withTheAuditOffEveryListingIsUncheckedAndHeldBack() {
        Verdict v = verdict(new Setup(false, true, Audit.NONE, 0, false, false, null, null));
        assertThat(v.blocks()).containsExactly(Block.UNCHECKED);
        assertThat(v.state()).isEqualTo("NONE");
        assertThat(v.mayGo()).isFalse();
        assertThat(v.trademarkChecked()).isFalse();
    }

    @Test
    void theSettingThatSwitchesTheBlockOffSwitchesTheGateOffWithTheAuditOnOrOff() {
        for (boolean auditOn : new boolean[] {true, false}) {
            for (Audit audit : Audit.values()) {
                for (TrademarkReason reason : TrademarkReason.values()) {
                    Verdict v = verdict(new Setup(auditOn, false, audit, 3, true, reason == TrademarkReason.RAN, reason, null));
                    assertThat(v.blocks()).as("audit " + auditOn + " " + audit + " " + reason).isEmpty();
                    assertThat(v.mayGo()).isTrue();
                }
            }
        }
    }

    @Test
    void theServiceSaysWhetherTheGateAndTheAuditAreOn() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditStore> off = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditStore> on = mock(ObjectProvider.class);
        when(on.getIfAvailable()).thenReturn(mock(AuditStore.class));
        WorkspaceOverrides overrides = mock(WorkspaceOverrides.class);
        ListingStatusService auditOffGateOn = new ListingStatusService(mock(WorkspaceAccess.class), mock(WorkspaceListings.class), overrides, off, true);
        assertThat(auditOffGateOn.gateActive()).isTrue();
        assertThat(auditOffGateOn.auditActive()).isFalse();
        ListingStatusService auditOnGateOff = new ListingStatusService(mock(WorkspaceAccess.class), mock(WorkspaceListings.class), overrides, on, false);
        assertThat(auditOnGateOff.gateActive()).isFalse();
        assertThat(auditOnGateOff.auditActive()).isTrue();
    }

    // ------------------------------------------------------------------ the reason of an ADMIN

    @Test
    void aReasonThatIsStillValidLetsAHeldBackListingGoAndOneForOtherTextDoesNot() {
        Set<Block> held = EnumSet.of(Block.ERROR);
        Verdict valid = verdict(new Setup(true, true, Audit.THIS_TEXT, 1, false, true, TrademarkReason.RAN, true));
        assertThat(valid.blocks()).isEqualTo(held);
        assertThat(valid.opened()).isNotNull();
        assertThat(valid.mayGo()).isTrue();
        Verdict expired = verdict(new Setup(true, true, Audit.THIS_TEXT, 1, false, true, TrademarkReason.RAN, false));
        assertThat(expired.opened()).isNull();
        assertThat(expired.mayGo()).isFalse();
    }

    @Test
    void aValidReasonOpensEveryKindOfHoldWithTheAuditOnOrOff() {
        Setup[] holds = {
                new Setup(true, true, Audit.NONE, 0, false, false, null, true),
                new Setup(true, true, Audit.OLDER_TEXT, 0, false, true, TrademarkReason.RAN, true),
                new Setup(true, true, Audit.THIS_TEXT, 0, false, false, TrademarkReason.UNAVAILABLE, true),
                new Setup(true, true, Audit.THIS_TEXT, 1, true, true, TrademarkReason.RAN, true),
                new Setup(false, true, Audit.NONE, 0, false, false, null, true)};
        for (Setup hold : holds) {
            Verdict v = verdict(hold);
            assertThat(v.blocks()).as(hold.toString()).isNotEmpty();
            assertThat(v.mayGo()).as(hold.toString()).isTrue();
        }
        Setup[] expired = {
                new Setup(true, true, Audit.NONE, 0, false, false, null, false),
                new Setup(false, true, Audit.NONE, 0, false, false, null, false)};
        for (Setup hold : expired) {
            assertThat(verdict(hold).mayGo()).as(hold.toString()).isFalse();
        }
    }

    @Test
    void aValidReasonOnAListingThatNothingHoldsBackChangesNothing() {
        Verdict v = verdict(new Setup(true, true, Audit.THIS_TEXT, 0, false, true, TrademarkReason.RAN, true));
        assertThat(v.blocks()).isEmpty();
        assertThat(v.mayGo()).isTrue();
    }
}
