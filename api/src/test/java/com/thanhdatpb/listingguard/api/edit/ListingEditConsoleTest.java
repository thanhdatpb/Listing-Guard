package com.thanhdatpb.listingguard.api.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListingEditConsoleTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CheckService rules = mock(CheckService.class);
    private final TrademarkCheckService trademarks = mock(TrademarkCheckService.class);
    private final ListingEditService edits = new ListingEditService(rules, trademarks, null, mapper);
    private final UUID client = UUID.randomUUID();
    private final ListingEditRequest request = new ListingEditRequest("ETSY",
            mapper.createObjectNode().put("title", "Before"), mapper.createObjectNode().put("title", "After"), null, null);

    @Test
    void anUnavailableCatalogDoesNotHideTheListingComparison() {
        CheckResult checked = mock(CheckResult.class);
        when(checked.readiness()).thenReturn(new CheckResult.ReadinessView(100, true, "READY", "test"));
        when(rules.checkDetailed(eq(client), any())).thenReturn(checked);
        when(trademarks.check(eq(client), any(CheckRequest.class)))
                .thenThrow(new TrademarkUnavailableException("No catalog"));

        var comparison = edits.checkConsole(client, request);

        assertThat(comparison.listingRules()).isEqualTo(ListingEditService.PackState.COMPLETE);
        assertThat(comparison.trademarkRisk()).isEqualTo(ListingEditService.PackState.UNAVAILABLE);
        assertThat(comparison.result().after().listingRules()).isSameAs(checked);
        assertThat(comparison.result().after().trademarkRisk()).isNull();
        assertThat(comparison.result().changedFields()).contains("title");
    }

    @Test
    void aQuotaErrorOnTheAfterSideCannotResolveTheBeforeFindings() {
        CheckResult before = mock(CheckResult.class);
        when(rules.checkDetailed(eq(client), any())).thenReturn(before)
                .thenThrow(new QuotaExceededException("FREE", 1, 1, Instant.EPOCH));
        when(trademarks.check(eq(client), any(CheckRequest.class)))
                .thenThrow(new TrademarkUnavailableException("No catalog"));

        var comparison = edits.checkConsole(client, request);

        assertThat(comparison.listingRules()).isEqualTo(ListingEditService.PackState.QUOTA);
        assertThat(comparison.result().before().listingRules()).isSameAs(before);
        assertThat(comparison.result().after().listingRules()).isNull();
        assertThat(comparison.result().modifiedFindings()).isEmpty();
        assertThat(comparison.result().changedFields()).contains("title");
    }
}
