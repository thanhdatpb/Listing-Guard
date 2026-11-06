package com.thanhdatpb.listingguard.api.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import com.thanhdatpb.listingguard.core.listing.PersonalizationQuestion;
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

    /**
     * SHA-256 of the canonical JSON the hasher produced for {@link #pinnedAmazon()} before {@code itemHighlights}
     * existed, computed independently of this code (the canonical JSON written by hand and hashed with .NET; the same
     * method reproduces {@link #ETSY_HASH}). If this fails, cached results of an Amazon listing without Item Highlights
     * stop matching: do not "fix" the expected value, fix the hasher.
     */
    private static final String AMAZON_HASH = "c772302dea8c23b978f36caa2d60268e48ec6853d4b6b517485558b0f8aae889";

    private final ContentHasher hasher = new ContentHasher();

    private static Listing pinnedAmazon() {
        return new Listing("Hardside luggage", "A suitcase.", List.of(), new Money(new BigDecimal("49.99"), "USD"), null,
                List.of(), List.of("https://example.com/1.jpg"), 5, null, null, "Roadster", List.of("One", "Two"),
                "carry on", "LUGGAGE");
    }

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
    void theHashOfAnAmazonListingWithoutItemHighlightsIsUnchanged() {
        assertThat(hasher.hash(pinnedAmazon())).isEqualTo(AMAZON_HASH);
    }

    @Test
    void itemHighlightsAbsentBlankOrNullDoNotChangeTheHashButAValueDoes() {
        Listing base = pinnedAmazon();
        Listing explicitNull = new Listing(base.title(), base.description(), base.tags(), base.price(), base.category(),
                base.materials(), base.images(), base.quantity(), base.whoMade(), base.whenMade(), base.brand(),
                base.bulletPoints(), base.searchTerms(), base.productType(), null);
        Listing withHighlights = new Listing(base.title(), base.description(), base.tags(), base.price(), base.category(),
                base.materials(), base.images(), base.quantity(), base.whoMade(), base.whenMade(), base.brand(),
                base.bulletPoints(), base.searchTerms(), base.productType(), "Hard shell");

        assertThat(hasher.hash(explicitNull)).isEqualTo(AMAZON_HASH);
        assertThat(hasher.hash(withHighlights)).isNotEqualTo(AMAZON_HASH).matches("[0-9a-f]{64}");
        assertThat(hasher.hash(withHighlights.withTitle("Other"))).isNotEqualTo(hasher.hash(base.withTitle("Other")));
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

    private static Listing etsyWith(List<PersonalizationQuestion> questions) {
        return new Listing("Handmade ceramic mug", "A nice mug for tea.", List.of("mug", "ceramic"),
                new Money(new BigDecimal("19.99"), "USD"), "Home > Mugs", List.of("clay"), List.of("https://example.com/1.jpg"),
                5, "i_did", "made_to_order", null, List.of(), null, null, null, questions);
    }

    private static PersonalizationQuestion text(String question) {
        return new PersonalizationQuestion("TEXT", question, null, true, 20, List.of(), null);
    }

    @Test
    void noPersonalizationQuestionsLeavesTheEtsyHashUnchanged() {
        assertThat(hasher.hash(etsyWith(List.of()))).isEqualTo(ETSY_HASH);
        assertThat(hasher.hash(etsyWith(null))).isEqualTo(ETSY_HASH);
    }

    @Test
    void personalizationQuestionsChangeTheHashAndEveryPartOfAQuestionCountsInIt() {
        String base = hasher.hash(etsyWith(List.of(text("Name"))));

        assertThat(base).isNotEqualTo(ETSY_HASH).matches("[0-9a-f]{64}");
        assertThat(hasher.hash(etsyWith(List.of(text("Name"))))).isEqualTo(base);
        assertThat(hasher.hash(etsyWith(List.of(text("Nickname"))))).isNotEqualTo(base);
        assertThat(hasher.hash(etsyWith(List.of(text("Name"), text("Date"))))).isNotEqualTo(base);
        assertThat(hasher.hash(etsyWith(List.of(new PersonalizationQuestion("TEXT", "Name", "Letters only", true, 20,
                List.of(), null))))).isNotEqualTo(base);
        assertThat(hasher.hash(etsyWith(List.of(new PersonalizationQuestion("TEXT", "Name", null, false, 20,
                List.of(), null))))).isNotEqualTo(base);
        assertThat(hasher.hash(etsyWith(List.of(new PersonalizationQuestion("TEXT", "Name", null, true, 21,
                List.of(), null))))).isNotEqualTo(base);
        assertThat(hasher.hash(etsyWith(List.of(new PersonalizationQuestion("DROPDOWN", "Name", null, true, 20,
                List.of("A"), null))))).isNotEqualTo(base);
        assertThat(hasher.hash(etsyWith(List.of(new PersonalizationQuestion("UPLOAD", "Name", null, true, 20,
                List.of(), 2))))).isNotEqualTo(base);
    }
}
