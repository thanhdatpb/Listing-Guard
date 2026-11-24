package com.thanhdatpb.listingguard.rag.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.listing.Listing;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyzerProfileTest {
    @Test
    void versionAndHashArePinnedAndSettingsAreDeeplyImmutable() {
        AnalyzerProfile profile = AnalyzerProfile.etsy();
        assertThat(profile.settings().version()).isEqualTo("etsy-policy-2026-11");
        assertThat(profile.hash()).isEqualTo("4bed1f38f3555b904995d62d2fc79a671aecf256097407e0aca0f97ecbf3361e")
                .isEqualTo(AnalyzerProfile.etsy().hash());
        assertThatThrownBy(() -> profile.settings().sources().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> profile.settings().probes().get("vi").clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void changedProfileNeedsANewPinnedVersion() throws IOException {
        var original = AnalyzerProfile.etsy();
        String path = "/analyzer/" + original.settings().version() + ".yaml";
        byte[] config;
        try (var input = getClass().getResourceAsStream(path)) {
            config = input.readAllBytes();
        }
        String newVersion = "etsy-policy-2026-12";
        byte[] edited = new String(config, StandardCharsets.UTF_8)
                .replace("topK: 3", "topK: 2").replace(original.settings().version(), newVersion)
                .getBytes(StandardCharsets.UTF_8);
        String hash = AnalyzerProfile.sha256(edited);
        assertThatThrownBy(() -> AnalyzerProfile.loadPinned(new ByteArrayInputStream(edited), newVersion, original.hash()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pinned hash");
        assertThatThrownBy(() -> AnalyzerProfile.loadPinned(new ByteArrayInputStream(edited), original.settings().version(), hash))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pinned version");
        var successor = AnalyzerProfile.loadPinned(new ByteArrayInputStream(edited), newVersion, hash);
        assertThat(successor.settings().version()).isEqualTo(newVersion);
        assertThat(successor.settings().topK()).isEqualTo(2);
        assertThat(successor.hash()).isNotEqualTo(original.hash());
    }

    @Test
    void buildsSixBoundedQueriesInTheRequestedLanguageAndSkipsOtherListingFields() {
        AnalyzerProfile profile = AnalyzerProfile.etsy();
        String title = "x".repeat(1999) + "🙂";
        Listing listing = new Listing(title, "d".repeat(70_000), Collections.nCopies(1_000, "tag"), null,
                "private category", List.of("private material"), List.of("private image"), null, null, null);
        List<String> vi = profile.queries(listing, "vi");
        assertThat(vi).hasSize(6);
        assertThat(vi.get(0)).hasSize(1999);
        assertThat(vi.get(1)).hasSize(4000);
        assertThat(vi.get(2)).contains("đối tác sản xuất");
        assertThat(vi.toString()).doesNotContain("private");
        assertThat(profile.queries(new Listing(null, null, List.of(), null, null, List.of(), List.of(), null, null, null), "en"))
                .hasSize(4).first().asString().contains("production partner");
    }
}
