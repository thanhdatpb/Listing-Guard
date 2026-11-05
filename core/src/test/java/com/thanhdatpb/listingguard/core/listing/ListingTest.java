package com.thanhdatpb.listingguard.core.listing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListingTest {

    private static final Listing AMAZON_STYLE = new Listing("Title", "Description", List.of("a", "b"),
            new Money(new BigDecimal("9.99"), "USD"), "cat", List.of("m"), List.of("https://example.com/1.jpg"), 3,
            "i_did", "made_to_order", "Brand", List.of("bullet"), "terms", "LUGGAGE");

    @Test
    void withTitleChangesOnlyTheTitle() {
        Listing copy = AMAZON_STYLE.withTitle("Other");

        assertThat(copy.title()).isEqualTo("Other");
        assertThat(copy.withTitle("Title")).isEqualTo(AMAZON_STYLE);
        assertThat(AMAZON_STYLE.title()).isEqualTo("Title");
    }

    @Test
    void withTagsChangesOnlyTheTags() {
        Listing copy = AMAZON_STYLE.withTags(List.of("x"));

        assertThat(copy.tags()).containsExactly("x");
        assertThat(copy.withTags(List.of("a", "b"))).isEqualTo(AMAZON_STYLE);
        assertThat(AMAZON_STYLE.tags()).containsExactly("a", "b");
    }
}
