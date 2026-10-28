package com.thanhdatpb.listingguard.api.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentHasherTest {

    /**
     * SHA-256 of the canonical JSON the hasher produced for this Etsy listing before the Amazon-only components
     * existed, computed independently of this code. If this fails, cached Etsy results stop matching: do not "fix" the
     * expected value, fix the hasher.
     */
    private static final String ETSY_HASH = "b4d4abfcd0822f4c115a0871e44b3bb2ad711d080756cb19ee642a6c3e8d0090";

    private final ContentHasher hasher = new ContentHasher();

    private static Listing etsy() {
        return new Listing("Handmade ceramic mug", "A nice mug for tea.", List.of("mug", "ceramic"),
                new Money(new BigDecimal("19.99"), "USD"), "Home > Mugs", List.of("clay"), List.of("https://example.com/1.jpg"),
                5, "i_did", "made_to_order");
    }

    private static Listing amazon(String brand, List<String> bullets, String searchTerms, String productType) {
        return new Listing("Hardside luggage", "A suitcase.", List.of(), new Money(new BigDecimal("49.99"), "USD"), null,
                List.of(), List.of("https://example.com/1.jpg"), 5, null, null, brand, bullets, searchTerms, productType);
    }

    @Test
    void theHashOfAnEtsyListingIsUnchangedByTheAmazonComponents() {
        assertThat(hasher.hash(etsy())).isEqualTo(ETSY_HASH);
    }

    @Test
    void emptyAmazonComponentsAreTheSameWhetherNullOrEmpty() {
        Listing explicit = new Listing("Handmade ceramic mug", "A nice mug for tea.", List.of("mug", "ceramic"),
                new Money(new BigDecimal("19.99"), "USD"), "Home > Mugs", List.of("clay"), List.of("https://example.com/1.jpg"),
                5, "i_did", "made_to_order", null, null, null, null);

        assertThat(hasher.hash(explicit)).isEqualTo(ETSY_HASH);
    }

    @Test
    void theTrailingZerosOfThePriceDoNotMatter() {
        Listing padded = new Listing("Handmade ceramic mug", "A nice mug for tea.", List.of("mug", "ceramic"),
                new Money(new BigDecimal("19.990"), "USD"), "Home > Mugs", List.of("clay"), List.of("https://example.com/1.jpg"),
                5, "i_did", "made_to_order");

        assertThat(hasher.hash(padded)).isEqualTo(ETSY_HASH);
    }

    @Test
    void everyAmazonComponentChangesTheHash() {
        String base = hasher.hash(amazon("Roadster", List.of("One"), "carry on", "LUGGAGE"));

        assertThat(hasher.hash(amazon("Other", List.of("One"), "carry on", "LUGGAGE"))).isNotEqualTo(base);
        assertThat(hasher.hash(amazon("Roadster", List.of("One", "Two"), "carry on", "LUGGAGE"))).isNotEqualTo(base);
        assertThat(hasher.hash(amazon("Roadster", List.of("One"), "carry-on", "LUGGAGE"))).isNotEqualTo(base);
        assertThat(hasher.hash(amazon("Roadster", List.of("One"), "carry on", "SUITCASE"))).isNotEqualTo(base);
    }

    @Test
    void presentAmazonComponentsDifferFromAbsentOnes() {
        String bare = hasher.hash(amazon(null, List.of(), null, null));

        assertThat(hasher.hash(amazon("Roadster", List.of(), null, null))).isNotEqualTo(bare);
        assertThat(hasher.hash(amazon(null, List.of("One"), null, null))).isNotEqualTo(bare);
        assertThat(hasher.hash(amazon(null, List.of(), "carry on", null))).isNotEqualTo(bare);
        assertThat(hasher.hash(amazon(null, List.of(), null, "LUGGAGE"))).isNotEqualTo(bare);
    }

    @Test
    void theSameAmazonListingAlwaysHashesTheSame() {
        assertThat(hasher.hash(amazon("Roadster", List.of("One"), "carry on", "LUGGAGE")))
                .isEqualTo(hasher.hash(amazon("Roadster", List.of("One"), "carry on", "LUGGAGE")))
                .matches("[0-9a-f]{64}");
    }
}
