package com.thanhdatpb.listingguard.trademark;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-009: fixed field order, NFC and trim before any match normalization. */
class CanonicalInputTest {

    private static final TrademarkCheckInput SAMPLE = new TrademarkCheckInput("Zorvex Mug",
            List.of("gift", "mug"), "A plain mug", "Acme", "Acme Shop", "Home");

    private static String hash(TrademarkCheckInput input) {
        return CanonicalInput.of(input).hash();
    }

    @Test
    void theHashOfAFixedInputIsPinned() {
        assertThat(hash(SAMPLE)).isEqualTo("19c6eafde6e711ebd319bab68479c1633809f267e5f320eb10d12d1d418d9f93");
    }

    @Test
    void fieldsAreInAFixedOrderWithTagsExpandedInPlace() {
        CanonicalInput canonical = CanonicalInput.of(SAMPLE);

        assertThat(canonical.fields()).extracting(CanonicalInput.Field::name).containsExactly("title", "tags[0]",
                "tags[1]", "description", "brand", "shop_name", "search_terms", "category", "product_type");
        assertThat(canonical.fields()).extracting(CanonicalInput.Field::text).containsExactly("Zorvex Mug", "gift",
                "mug", "A plain mug", "Acme", "Acme Shop", "", "Home", "");
    }

    @Test
    void theHashFormatIsV2BecauseTheProductTypeJoinedTheInput() {
        assertThat(CanonicalInput.HASH_FORMAT).isEqualTo("listing-guard/trademark-input/v2");
    }

    @Test
    void theProductTypeTakesPartInTheHashAndIsNeverScanned() {
        TrademarkCheckInput withType = new TrademarkCheckInput("Zorvex Mug", List.of("gift", "mug"), List.of(),
                "A plain mug", "Acme", "Acme Shop", null, "Home", "mug");
        TrademarkCheckInput otherType = new TrademarkCheckInput("Zorvex Mug", List.of("gift", "mug"), List.of(),
                "A plain mug", "Acme", "Acme Shop", null, "Home", "toy");

        assertThat(hash(withType)).isNotEqualTo(hash(SAMPLE)).isNotEqualTo(hash(otherType));
        assertThat(CanonicalInput.of(withType).productType()).isEqualTo("mug");
        assertThat(CanonicalInput.of(SAMPLE).productType()).isNull();
    }

    @Test
    void aBlankProductTypeIsNoProductTypeAndANonBlankOneIsStripped() {
        TrademarkCheckInput blank = new TrademarkCheckInput("t", List.of(), List.of(), null, null, null, null, null,
                " \t ");
        TrademarkCheckInput none = TrademarkCheckInput.ofTitle("t");
        TrademarkCheckInput padded = new TrademarkCheckInput("t", List.of(), List.of(), null, null, null, null, null,
                "  mug ");

        assertThat(hash(blank)).isEqualTo(hash(none));
        assertThat(CanonicalInput.of(blank).productType()).isNull();
        assertThat(CanonicalInput.of(padded).productType()).isEqualTo("mug");
        assertThat(hash(padded)).isEqualTo(hash(new TrademarkCheckInput("t", List.of(), List.of(), null, null, null,
                null, null, "mug")));
    }

    @Test
    void theProductTypeCannotImitateAnotherField() {
        assertThat(hash(new TrademarkCheckInput("t", List.of(), List.of(), null, null, null, null, "mug", null)))
                .isNotEqualTo(hash(new TrademarkCheckInput("t", List.of(), List.of(), null, null, null, null, null,
                        "mug")));
    }

    @Test
    void unicodeNormalFormsOfTheSameTextHashTheSame() {
        String composed = Normalizer.normalize("Café Zorvex", Normalizer.Form.NFC);
        String decomposed = Normalizer.normalize("Café Zorvex", Normalizer.Form.NFD);
        assertThat(composed).isNotEqualTo(decomposed);

        assertThat(hash(TrademarkCheckInput.ofTitle(composed))).isEqualTo(hash(TrademarkCheckInput.ofTitle(decomposed)));
        assertThat(CanonicalInput.of(TrademarkCheckInput.ofTitle(decomposed)).fields().get(0).text())
                .isEqualTo(composed);
    }

    @Test
    void surroundingWhitespaceIsTrimmedButInnerWhitespaceIsNot() {
        assertThat(hash(TrademarkCheckInput.ofTitle("  Zorvex Mug \n"))).isEqualTo(hash(TrademarkCheckInput.ofTitle("Zorvex Mug")));
        assertThat(hash(TrademarkCheckInput.ofTitle("Zorvex  Mug"))).isNotEqualTo(hash(TrademarkCheckInput.ofTitle("Zorvex Mug")));
    }

    @Test
    void theHashIsTakenBeforeMatchNormalization() {
        List<String> titles = List.of("Zorvex", "zorvex", "Z0rvex", "Zörvex", "Zorvex!", "Zorvеx");

        assertThat(titles.stream().map(title -> hash(TrademarkCheckInput.ofTitle(title))).distinct())
                .hasSameSizeAs(titles);
    }

    @Test
    void nullAndEmptyAreTheSame() {
        assertThat(hash(new TrademarkCheckInput(null, null, null, null, null, null)))
                .isEqualTo(hash(new TrademarkCheckInput("", List.of(), "", "", "", "")));
    }

    @Test
    void everyFieldTakesPart() {
        String base = hash(SAMPLE);

        assertThat(hash(new TrademarkCheckInput("x", SAMPLE.tags(), SAMPLE.description(), SAMPLE.brand(),
                SAMPLE.shopName(), SAMPLE.category()))).isNotEqualTo(base);
        assertThat(hash(new TrademarkCheckInput(SAMPLE.title(), List.of("gift"), SAMPLE.description(), SAMPLE.brand(),
                SAMPLE.shopName(), SAMPLE.category()))).isNotEqualTo(base);
        assertThat(hash(new TrademarkCheckInput(SAMPLE.title(), SAMPLE.tags(), "x", SAMPLE.brand(),
                SAMPLE.shopName(), SAMPLE.category()))).isNotEqualTo(base);
        assertThat(hash(new TrademarkCheckInput(SAMPLE.title(), SAMPLE.tags(), SAMPLE.description(), "x",
                SAMPLE.shopName(), SAMPLE.category()))).isNotEqualTo(base);
        assertThat(hash(new TrademarkCheckInput(SAMPLE.title(), SAMPLE.tags(), SAMPLE.description(), SAMPLE.brand(),
                "x", SAMPLE.category()))).isNotEqualTo(base);
        assertThat(hash(new TrademarkCheckInput(SAMPLE.title(), SAMPLE.tags(), SAMPLE.description(), SAMPLE.brand(),
                SAMPLE.shopName(), "x"))).isNotEqualTo(base);
    }

    @Test
    void movingTextBetweenFieldsChangesTheHash() {
        assertThat(hash(new TrademarkCheckInput("a", List.of(), "b", null, null, null)))
                .isNotEqualTo(hash(new TrademarkCheckInput("b", List.of(), "a", null, null, null)));
        assertThat(hash(new TrademarkCheckInput("a", List.of(), null, "b", null, null)))
                .isNotEqualTo(hash(new TrademarkCheckInput("a", List.of(), null, null, "b", null)));
    }

    @Test
    void theOrderAndBoundariesOfTagsMatter() {
        assertThat(hash(new TrademarkCheckInput("t", List.of("a", "b"), null, null, null, null)))
                .isNotEqualTo(hash(new TrademarkCheckInput("t", List.of("b", "a"), null, null, null, null)));
        assertThat(hash(new TrademarkCheckInput("t", List.of("ab", "c"), null, null, null, null)))
                .isNotEqualTo(hash(new TrademarkCheckInput("t", List.of("a", "bc"), null, null, null, null)));
        assertThat(hash(new TrademarkCheckInput("t", List.of(), null, null, null, null)))
                .isNotEqualTo(hash(new TrademarkCheckInput("t", List.of(""), null, null, null, null)));
    }

    @Test
    void aBlankTagKeepsItsPosition() {
        CanonicalInput canonical = CanonicalInput.of(new TrademarkCheckInput("t", List.of("a", "  ", "c"), null, null,
                null, null));

        assertThat(canonical.fields()).extracting(CanonicalInput.Field::name).contains("tags[0]", "tags[1]", "tags[2]");
        assertThat(canonical.fields().get(2).text()).isEmpty();
    }

    @Test
    void lineBreaksInsideATextCannotImitateAnotherField() {
        assertThat(hash(new TrademarkCheckInput("a\ndescription:1:b", List.of(), null, null, null, null)))
                .isNotEqualTo(hash(new TrademarkCheckInput("a", List.of(), "b", null, null, null)));
    }

    @Test
    void theHashIsHex() {
        assertThat(hash(SAMPLE)).matches("[0-9a-f]{64}");
    }

    private static final TrademarkCheckInput AMAZON = new TrademarkCheckInput("Title", List.of("tag"),
            List.of("first bullet", "second bullet"), "Description", "Brand", "Shop", "search terms here", "Cat");

    @Test
    void amazonFieldsHaveAFixedPositionAndKeepTheirIndexes() {
        CanonicalInput canonical = CanonicalInput.of(AMAZON);

        assertThat(canonical.fields()).extracting(CanonicalInput.Field::name).containsExactly("title", "tags[0]",
                "bullet_points[0]", "bullet_points[1]", "description", "brand", "shop_name", "search_terms", "category",
                "product_type");
        assertThat(canonical.fields()).extracting(CanonicalInput.Field::text).containsExactly("Title", "tag",
                "first bullet", "second bullet", "Description", "Brand", "Shop", "search terms here", "Cat", "");
        CanonicalInput blank = CanonicalInput.of(new TrademarkCheckInput("t", List.of(), List.of("a", " ", "c"), null,
                null, null, null, null));
        assertThat(blank.fields()).extracting(CanonicalInput.Field::name).contains("bullet_points[1]");
        assertThat(blank.fields().get(2).text()).isEmpty();
    }

    @Test
    void bulletPointsAndSearchTermsTakePartInTheHash() {
        String base = hash(AMAZON);

        assertThat(hash(new TrademarkCheckInput("Title", List.of("tag"), List.of("first bullet"), "Description",
                "Brand", "Shop", "search terms here", "Cat"))).isNotEqualTo(base);
        assertThat(hash(new TrademarkCheckInput("Title", List.of("tag"), List.of("second bullet", "first bullet"),
                "Description", "Brand", "Shop", "search terms here", "Cat"))).isNotEqualTo(base);
        assertThat(hash(new TrademarkCheckInput("Title", List.of("tag"), List.of("first bullet", "second bullet"),
                "Description", "Brand", "Shop", "other", "Cat"))).isNotEqualTo(base);
    }

    @Test
    void aBulletPointCannotImitateATagOrASearchTerm() {
        assertThat(hash(new TrademarkCheckInput("t", List.of("a"), List.of(), null, null, null, null, null)))
                .isNotEqualTo(hash(new TrademarkCheckInput("t", List.of(), List.of("a"), null, null, null, null, null)));
        assertThat(hash(new TrademarkCheckInput("t", List.of(), List.of("a"), null, null, null, null, null)))
                .isNotEqualTo(hash(new TrademarkCheckInput("t", List.of(), List.of(), null, null, null, "a", null)));
        assertThat(hash(new TrademarkCheckInput("t", List.of(), List.of("ab", "c"), null, null, null, null, null)))
                .isNotEqualTo(hash(new TrademarkCheckInput("t", List.of(), List.of("a", "bc"), null, null, null, null,
                        null)));
    }
}
