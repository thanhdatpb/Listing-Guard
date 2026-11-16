package com.thanhdatpb.listingguard.trademark.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The watchlist settings file (ADR-031): strict, immutable, HIGH by default. */
class WatchlistSettingsTest {

    /** A published settings file is immutable (ADR-002): a change is a new file with a new version. */
    private static final String SHIPPED_HASH = "f9fb9eebfb73989923beb705566aa53803c8757d005cae3441b929adb8a6f8f6";

    private static String shipped() {
        try (InputStream in = WatchlistSettingsTest.class.getResourceAsStream("/" + WatchlistSettings.SHIPPED)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theShippedSettingsAreHighAndPinned() {
        WatchlistSettings settings = WatchlistSettings.loadClasspath(WatchlistSettings.SHIPPED);

        assertThat(settings.version()).isEqualTo("trademark-watchlist-2026-11");
        assertThat(settings.minimumRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(settings.contentHash()).isEqualTo(SHIPPED_HASH);
        assertThat(settings.fingerprintComponent()).isEqualTo(settings.version() + ":" + SHIPPED_HASH);
    }

    @Test
    void lineEndingsDoNotChangeTheHash() {
        assertThat(WatchlistSettings.parse(shipped().replace("\n", "\r\n")).contentHash()).isEqualTo(SHIPPED_HASH);
    }

    @Test
    void mistakesFailAtLoadTime() {
        assertThatThrownBy(() -> WatchlistSettings.parse(shipped().replace("minimum_risk: HIGH", "minimum_risk: UNKNOWN")))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("minimum_risk");
        assertThatThrownBy(() -> WatchlistSettings.parse(shipped().replace("status: unverified", "status: verified")))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("unverified");
        assertThatThrownBy(() -> WatchlistSettings.parse(shipped().replace("{mark}' trong", "{brand}' trong")))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("brand");
        assertThatThrownBy(() -> WatchlistSettings.parse(shipped() + "\nextra: 1\n"))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("extra");
        assertThatThrownBy(() -> WatchlistSettings.parse(shipped().replace("pack_id: trademark-risk", "pack_id: other")))
                .isInstanceOf(TrademarkRulesetException.class).hasMessageContaining("pack_id");
    }
}
