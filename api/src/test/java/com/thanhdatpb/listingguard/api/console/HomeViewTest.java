package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.console.HomeView.Attention;
import com.thanhdatpb.listingguard.api.console.HomeView.AttentionKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** What names a result on the home page's list (ADR-041). Every text here is invented. */
class HomeViewTest {

    private static Attention item(String sku, String subject, String ruleId) {
        return new Attention(AttentionKind.ERROR, sku, subject, ruleId, "message", "ETSY", Instant.EPOCH);
    }

    @Test
    void theSkuNamesTheResultWhenThereIsOne() {
        assertThat(item("MUG-01", "A title", "r-1").label()).isEqualTo("MUG-01");
    }

    @Test
    void withoutASkuTheValueTheFindingIsAboutNamesTheResultInOneLine() {
        assertThat(item(null, "Blue   mug" + System.lineSeparator() + " small", "r-1").label()).isEqualTo("Blue mug small");
        assertThat(item(" ", "Blue mug", "r-1").label()).isEqualTo("Blue mug");
    }

    @Test
    void aLongValueIsCutAtAWordWithAnEllipsis() {
        String label = item(null, "Handmade ceramic mug with a long handle and a sky blue glaze for the morning coffee and tea", "r-1").label();

        assertThat(label).endsWith("…");
        assertThat(label.codePointCount(0, label.length())).isLessThanOrEqualTo(Attention.LABEL_CHARS);
        assertThat(label).isEqualTo("Handmade ceramic mug with a long handle and a sky blue…");
    }

    @Test
    void withNeitherTheRuleIdIsTheLastName() {
        assertThat(item(null, null, "r-1").label()).isEqualTo("r-1");
        assertThat(item(null, "  ", "r-1").label()).isEqualTo("r-1");
    }
}
