package com.thanhdatpb.listingguard.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.client.model.CheckRequest;
import com.thanhdatpb.listingguard.client.model.PersonalizationScreeningRequest;
import com.thanhdatpb.listingguard.client.model.PersonalizationValue;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What the builder accepts and refuses, and that nothing prints a secret or a listing. */
class BuilderTest {

    private static ListingGuardClient.Builder valid() {
        return ListingGuardClient.builder().baseUrl("https://listing-api.example.com").apiKey(ApiStub.KEY);
    }

    @Test
    void aMinimalClientBuildsAndItsToStringHidesTheKey() {
        ListingGuardClient client = valid().build();

        assertThat(client.toString()).doesNotContain(ApiStub.KEY).contains("<hidden>").contains("listing-api.example.com");
        assertThat(valid().toString()).doesNotContain(ApiStub.KEY);
        client.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080/", "http://[::1]:8080", "https://x.example"})
    void httpsOrLoopbackIsAccepted(String url) {
        assertThat(valid().baseUrl(url).build()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://listing-api.example.com", "http://10.0.0.5:8080", "ftp://x.example",
            "https://user:pass@x.example", "https://x.example/?q=1", "https://x.example/#f", "not a url", "https://"})
    void anUnsafeOrInvalidBaseUrlIsRefusedWithoutRepeatingIt(String url) {
        assertThatThrownBy(() -> valid().baseUrl(url).build())
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("pass").doesNotContain("example.com"));
    }

    @Test
    void cleartextToAPrivateHostNeedsTheExplicitSetting() {
        assertThat(valid().baseUrl("http://10.0.0.5:8080").allowInsecureHttp(true).build()).isNotNull();
    }

    @Test
    void aMissingBaseUrlOrKeyIsRefused() {
        assertThatThrownBy(() -> ListingGuardClient.builder().apiKey(ApiStub.KEY).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("baseUrl");
        assertThatThrownBy(() -> ListingGuardClient.builder().baseUrl("https://x.example").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("apiKey");
        assertThatThrownBy(() -> valid().apiKey("   ").build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyWithCharactersAHeaderCannotCarryIsRefusedWithoutPrintingIt() {
        String bad = "lg_secret with space\r\nX-Injected: 1";
        assertThatThrownBy(() -> valid().apiKey(bad).build())
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("lg_secret"));
    }

    @Test
    void badSettingsAreRefused() {
        assertThatThrownBy(() -> valid().timeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valid().timeout(Duration.ofMinutes(11))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valid().maxResponseBytes(10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valid().userAgent("bad\nagent")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valid().cloudflareAccess("id", "has space")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valid().cloudflareAccess(null, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valid().retry(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRawRequestValidatesItsParts() {
        assertThatThrownBy(() -> RawRequest.post(Operation.USAGE, "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RawRequest.usage().withRequestId("has space")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RawRequest.post(Operation.CHECKS, "{}").withIfNoneMatch("x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RawRequest.post(Operation.CHECKS, "{\"secret\":1}").toString()).doesNotContain("secret");
    }

    @Test
    void requestTypesNeverShowTheListingOrTheBuyersText() {
        assertThat(CheckRequest.of("ETSY", Map.of("title", "SECRET-TITLE")).toString()).doesNotContain("SECRET-TITLE");
        assertThat(TrademarkCheckRequest.of("ETSY", Map.of("title", "SECRET-TITLE")).toString()).doesNotContain("SECRET");
        assertThat(PersonalizationScreeningRequest.of("ETSY", List.of(PersonalizationValue.of("name", "SECRET-NAME")))
                .toString()).doesNotContain("SECRET");
        assertThat(PersonalizationValue.text("SECRET-NAME").toString()).doesNotContain("SECRET");
    }

    @Test
    void aListingGivenAsJsonTextMustBeOneObjectAndTheMessageNeverQuotesIt() {
        assertThatThrownBy(() -> CheckRequest.ofJson("ETSY", "[\"SECRET\"]"))
                .isInstanceOf(IllegalArgumentException.class).satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET"));
        assertThatThrownBy(() -> CheckRequest.ofJson("ETSY", "{\"a\":\"SECRET\""))
                .isInstanceOf(IllegalArgumentException.class).satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET"));
        assertThatThrownBy(() -> CheckRequest.ofJson("ETSY", "{} {}")).isInstanceOf(IllegalArgumentException.class);
    }
}
