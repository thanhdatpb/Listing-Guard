package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class PilotDraftTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);
    private final UUID client = UUID.randomUUID();
    private final MockHttpSession session = new MockHttpSession();

    @Test
    void countsOnlyChangedCheckedRowsAtTheSamePositionAndRejectsReplays() {
        var first = advance(clock, client, null, Map.of(1, row("A", "previous"), 2, row("B", "previous"), 3, row("C", "same")));
        var revised = Map.of(1, row("A", "edited"), 2, row("D", "new listing"), 3, row("C", "same"));
        assertThat(advance(clock, client, first.id(), revised).changed()).containsExactly(1);
        assertThat(advance(clock, client, first.id(), revised).changed()).isEmpty();
    }

    @Test
    void doesNotCompareAcrossTenantOrAfterTimeout() {
        var original = Map.of(1, row("A", "previous"));
        var edited = Map.of(1, row("A", "edited"));
        var first = advance(clock, client, null, original);
        assertThat(advance(clock, UUID.randomUUID(), first.id(), edited).changed()).isEmpty();
        var second = advance(clock, client, null, original);
        assertThat(advance(Clock.offset(clock, Duration.ofMinutes(30)), client, second.id(), edited).changed()).isEmpty();
    }

    private PilotDraft.Update advance(Clock at, UUID tenant, String continuation, Map<Integer, PilotDraft.Fingerprint> rows) {
        return PilotDraft.advance(session, PilotDraft.Surface.BULK, at, tenant, "ETSY", continuation, rows);
    }

    private static PilotDraft.Fingerprint row(String sku, String listing) {
        return PilotDraft.Fingerprint.of(sku, listing);
    }
}
