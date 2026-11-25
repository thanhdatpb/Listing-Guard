package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.thanhdatpb.listingguard.api.console.bulk.BulkResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrademarkFindingHighlightsTest {
    @Test
    void highlightsThreeRisksAndKeepsOtherCardsAndWeakEvidenceAccessible() {
        var cards = new FindingCards(mock(ConsoleMessages.class), mock(FindingSources.class));
        var result = cards.bulkTrademark(List.of(
                item("low", "weak-one", true),
                item("medium", "medium-one", false),
                item("high", "high-one", false),
                item("medium", "medium-two", false),
                item("high", "high-two", false),
                item("low", "weak-two", true)), "ETSY");
        assertThat(result.primary()).extracting(FindingCard::message).containsExactly("high-one", "high-two", "medium-one");
        assertThat(result.more()).extracting(FindingCard::message).containsExactly("medium-two");
        assertThat(result.weak()).extracting(FindingCard::message).containsExactly("weak-one", "weak-two");
    }

    private static BulkResult.Item item(String level, String message, boolean weak) {
        return new BulkResult.Item(level, level, "catalog_exact", "title", message, "example", null,
                null, null, null, null, null, -1, -1, weak);
    }
}
