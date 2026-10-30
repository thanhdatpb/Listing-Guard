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
        assertThat(hash(SAMPLE)).isEqualTo("74e8cd5da0c43b2acabe7d924510975b7ccf74cebcfa8d92bd77193178e25dac");
    }

    @Test
    void fieldsAreInAFixedOrderWithTagsExpandedInPlace() {
        CanonicalInput canonical = CanonicalInput.of(SAMPLE);

        assertThat(canonical.fields()).extracting(CanonicalInput.Field::name).containsExactly("title", "tags[0]",
                "tags[1]", "description", "brand", "shop_name", "category");
        assertThat(canonical.fields()).extracting(CanonicalInput.Field::text).containsExactly("Zorvex Mug", "gift",
                "mug", "A plain mug", "Acme", "Acme Shop", "Home");
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
}
