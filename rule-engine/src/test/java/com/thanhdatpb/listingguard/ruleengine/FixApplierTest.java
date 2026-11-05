package com.thanhdatpb.listingguard.ruleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.FixSuggestion;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FixApplierTest {

    private static final Listing LISTING = new Listing("Long title here", "d", List.of("a", "b", "c"),
            new Money(new BigDecimal("1.00"), "USD"), "c", List.of(), List.of(), 1, "i_did", "made_to_order");

    private static Finding finding(String field, String value, FixSuggestion fix) {
        return new Finding("r", Severity.ERROR, field, value, "m", "s", "v", fix);
    }

    @Test
    void replacesTheTitle() {
        Listing changed = FixApplier.apply(LISTING,
                finding("title", "Long title here", FixSuggestion.replace("d", "Long title"))).orElseThrow();

        assertThat(changed.title()).isEqualTo("Long title");
        assertThat(changed.tags()).containsExactly("a", "b", "c");
    }

    @Test
    void replacesOneTag() {
        Listing changed = FixApplier.apply(LISTING, finding("tags[1]", "b", FixSuggestion.replace("d", "bb"))).orElseThrow();

        assertThat(changed.tags()).containsExactly("a", "bb", "c");
    }

    @Test
    void removesOneTag() {
        Listing changed = FixApplier.apply(LISTING, finding("tags[1]", "b", FixSuggestion.remove("d"))).orElseThrow();

        assertThat(changed.tags()).containsExactly("a", "c");
    }

    @Test
    void neverChangesTheListingItWasGiven() {
        FixApplier.apply(LISTING, finding("tags[0]", "a", FixSuggestion.remove("d")));
        FixApplier.apply(LISTING, finding("title", "Long title here", FixSuggestion.replace("d", "x")));

        assertThat(LISTING.tags()).containsExactly("a", "b", "c");
        assertThat(LISTING.title()).isEqualTo("Long title here");
    }

    @Test
    void refusesAFindingWithoutAFix() {
        assertThat(FixApplier.apply(LISTING, finding("title", "Long title here", null))).isEmpty();
    }

    @Test
    void refusesAStoredValueThatIsNotTheFindingsValue() {
        assertThat(FixApplier.apply(LISTING, finding("title", "Another title", FixSuggestion.replace("d", "x")))).isEmpty();
        assertThat(FixApplier.apply(LISTING, finding("tags[1]", "zzz", FixSuggestion.remove("d")))).isEmpty();
    }

    @Test
    void refusesATagIndexOutOfRange() {
        assertThat(FixApplier.apply(LISTING, finding("tags[3]", "c", FixSuggestion.remove("d")))).isEmpty();
        assertThat(FixApplier.apply(LISTING, finding("tags[99999999999]", "c", FixSuggestion.remove("d")))).isEmpty();
    }

    @Test
    void refusesARemoveOnTheTitle() {
        assertThat(FixApplier.apply(LISTING, finding("title", "Long title here", FixSuggestion.remove("d")))).isEmpty();
    }

    @Test
    void refusesAFieldItDoesNotKnow() {
        assertThat(FixApplier.apply(LISTING, finding("description", "d", FixSuggestion.replace("d", "x")))).isEmpty();
        assertThat(FixApplier.apply(LISTING, finding("tags", null, FixSuggestion.remove("d")))).isEmpty();
        assertThat(FixApplier.apply(LISTING, finding(null, "x", FixSuggestion.remove("d")))).isEmpty();
    }
}
