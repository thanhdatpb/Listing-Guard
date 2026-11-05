package com.thanhdatpb.listingguard.core.finding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FixSuggestionTest {

    @Test
    void replaceCarriesTheProposedValue() {
        FixSuggestion fix = FixSuggestion.replace("Cắt tiêu đề.", "Handmade mug");

        assertThat(fix.action()).isEqualTo(FixAction.REPLACE);
        assertThat(fix.description()).isEqualTo("Cắt tiêu đề.");
        assertThat(fix.proposedValue()).isEqualTo("Handmade mug");
    }

    @Test
    void removeHasNoProposedValue() {
        FixSuggestion fix = FixSuggestion.remove("Xoá tag trùng.");

        assertThat(fix.action()).isEqualTo(FixAction.REMOVE);
        assertThat(fix.proposedValue()).isNull();
    }

    @Test
    void replaceWithoutAValueIsRefused() {
        assertThatThrownBy(() -> new FixSuggestion(FixAction.REPLACE, "d", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixSuggestion(FixAction.REPLACE, "d", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeWithAValueIsRefused() {
        assertThatThrownBy(() -> new FixSuggestion(FixAction.REMOVE, "d", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDescriptionIsRequired() {
        assertThatThrownBy(() -> FixSuggestion.remove(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FixSuggestion.remove(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anActionIsRequired() {
        assertThatThrownBy(() -> new FixSuggestion(null, "d", null)).isInstanceOf(NullPointerException.class);
    }
}
