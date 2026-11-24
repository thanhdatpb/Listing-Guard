package com.thanhdatpb.listingguard.api.console.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class PodProfilesTest {
    @Test
    void shippedVersionsAreImmutableAndNeverUsePlaceholderPrintLimits() throws Exception {
        var profiles = new PodProfiles(new ObjectMapper(), new PathMatchingResourcePatternResolver());
        var shirt = profiles.find("t-shirt-v1");
        var ornament = profiles.find("personalized-ornament-v1");
        assertThat(shirt.sha256()).isEqualTo("103148a39db58d916c098b53052596841150accc1450c5837d25d8fbde7b5d6a");
        assertThat(ornament.sha256()).isEqualTo("c2147da13cddd9dc048e926df6d4d8dcab90cfa2ee1e943644a2f08447d90e54");
        assertThat(profiles.find("t-shirt")).isNull();
        assertThat(profiles.released()).contains(shirt.profile(), ornament.profile());
        assertThat(List.of(shirt.profile(), ornament.profile())).allSatisfy(profile -> {
            assertThat(profile.marketplaces()).containsExactly(EditorMarketplace.ETSY, EditorMarketplace.AMAZON);
            assertThat(profile.printArea().sourceStatus()).isEqualTo(PodProfiles.Verification.UNVERIFIED);
            assertThat(profile.printArea().maxWidthMm()).isNull();
            assertThat(profile.printArea().maxHeightMm()).isNull();
        });
        assertThat(shirt.profile().variationRules()).containsExactly("sku", "size", "color");
        assertThat(ornament.profile().personalizationFields()).containsExactly("text");
        assertThatThrownBy(() -> shirt.profile().variationRules().add("brand"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PodProfiles.PrintArea(PodProfiles.Verification.VERIFIED, null, BigDecimal.TEN, BigDecimal.TEN));
    }

    @Test
    void allowsMissingFieldsButRejectsMalformedAndUnboundedInputBeforeCheck() {
        var draft = new PodDraft("t-shirt-v1", "shirt-1 | M | Blue\nshirt-2", "", "", "18.20", "", false, false);
        assertThat(draft.rows()).containsExactly(new PodDraft.Row("shirt-1", "M", "Blue", ""),
                new PodDraft.Row("shirt-2", "", "", ""));
        assertThat(draft.width()).isEqualByComparingTo("18.2");
        assertThat(draft.height()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PodDraft("", "a|b|c|d|e", "", "", "", "", false, false).rows());
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PodDraft("", "sku\n".repeat(31), "", "", "", "", false, false).rows());
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PodDraft("", "", "", "", "1e99", "", false, false).width());
    }
}
