package com.thanhdatpb.listingguard.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.client.ApiStub.Reply;
import com.thanhdatpb.listingguard.client.model.CatalogState;
import com.thanhdatpb.listingguard.client.model.CheckRequest;
import com.thanhdatpb.listingguard.client.model.Completeness;
import com.thanhdatpb.listingguard.client.model.MatchType;
import com.thanhdatpb.listingguard.client.model.RiskLevel;
import com.thanhdatpb.listingguard.client.model.Severity;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** A newer server: unknown members and enum values are read, and the version and deprecation headers reach the listener. */
class CompatibilityTest {

    @Test
    void unknownMembersAreIgnoredAndUnknownEnumValuesAreUnrecognizedNeverAKnownOne() {
        String body = """
                {"checkId":"0b9f6c1e-2d3a-4b5c-8d7e-6f5a4b3c2d1e","marketplace":"ETSY","completeness":"PARTIAL",
                 "riskLevel":"CRITICAL","newTopLevel":{"nested":[1,2,3]},"cached":false,
                 "findings":[{"ruleId":"r","risk":"SEVERE","matchType":"PHONETIC","start":0,"end":1,"brandNew":true}]}""";
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, body))) {
            var result = stub.client().trademarkChecks(TrademarkCheckRequest.of("ETSY", Map.of()));

            assertThat(result.completeness()).isEqualTo(Completeness.UNRECOGNIZED);
            assertThat(result.riskLevel()).isEqualTo(RiskLevel.UNRECOGNIZED).isNotEqualTo(RiskLevel.LOW);
            assertThat(result.findings()).singleElement().satisfies(f -> {
                assertThat(f.risk()).isEqualTo(RiskLevel.UNRECOGNIZED);
                assertThat(f.matchType()).isEqualTo(MatchType.UNRECOGNIZED);
            });
            assertThat(result.failedRuleIds()).as("a missing list is empty").isEmpty();
            assertThat(result.disclaimer()).as("a missing member is null").isNull();
        }
    }

    @Test
    void anUnknownSeverityAndCatalogStateAreUnrecognized() {
        String check = "{\"findings\":[{\"ruleId\":\"r\",\"severity\":\"BLOCKER\",\"source\":\"HUMAN\"}]}";
        String caps = "{\"trademarkCatalog\":{\"state\":\"WARMING_UP\",\"demo\":false},\"packs\":null}";
        try (ApiStub stub = new ApiStub().replying(r -> Reply.json(200, r.path().endsWith("checks") ? check : caps))) {
            var result = stub.client().checks(CheckRequest.of("ETSY", Map.of()));
            var capabilities = stub.client().capabilities();

            assertThat(result.findings().get(0).severity()).isEqualTo(Severity.UNRECOGNIZED);
            assertThat(capabilities.trademarkCatalog().state()).isEqualTo(CatalogState.UNRECOGNIZED);
            assertThat(capabilities.packs()).isEmpty();
        }
    }

    @Test
    void theServerVersionAndADeprecationReachTheListenerOncePerValue() {
        List<ServerVersionNotice> versions = new CopyOnWriteArrayList<>();
        List<DeprecationNotice> deprecations = new CopyOnWriteArrayList<>();
        ListingGuardListener listener = new ListingGuardListener() {
            @Override
            public void onServerVersion(ServerVersionNotice notice) {
                versions.add(notice);
            }

            @Override
            public void onDeprecation(DeprecationNotice notice) {
                deprecations.add(notice);
            }
        };
        try (ApiStub stub = new ApiStub()
                .then(Reply.json(200, "{}").with("X-Listing-Guard-Version", "0.8.0"),
                        Reply.json(200, "{}").with("X-Listing-Guard-Version", "0.8.0"),
                        Reply.json(200, "{}").with("X-Listing-Guard-Version", "0.9.0")
                                .with("Deprecation", "@1798761600").with("Sunset", "Fri, 01 Jan 2027 00:00:00 GMT")
                                .with("Link", "<https://example.test/migrate>; rel=\"deprecation\""),
                        Reply.json(200, "{}").with("Deprecation", "@1798761600"))
                .replying(Reply.json(200, "{}"))) {
            ListingGuardClient client = stub.builder().listener(listener).build();

            for (int i = 0; i < 4; i++) {
                client.usage();
            }

            assertThat(versions).extracting(ServerVersionNotice::serverVersion).containsExactly("0.8.0", "0.9.0");
            assertThat(versions.get(0).sdkVersion()).isEqualTo(ListingGuardClient.sdkVersion());
            assertThat(versions.get(0).operation()).isEqualTo(Operation.USAGE);
            assertThat(deprecations).singleElement().satisfies(d -> {
                assertThat(d.operation()).isEqualTo(Operation.USAGE);
                assertThat(d.deprecation()).isEqualTo("@1798761600");
                assertThat(d.sunset()).isEqualTo("Fri, 01 Jan 2027 00:00:00 GMT");
                assertThat(d.link()).contains("rel=\"deprecation\"");
                assertThat(d.requestId()).isNotNull();
            });
        }
    }

    @Test
    void aListenerThatThrowsNeverBreaksACall() {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, "{\"plan\":\"FREE\"}")
                .with("X-Listing-Guard-Version", "1").with("Deprecation", "true"))) {
            ListingGuardClient client = stub.builder().listener(new ListingGuardListener() {
                @Override
                public void onServerVersion(ServerVersionNotice notice) {
                    throw new IllegalStateException("boom");
                }

                @Override
                public void onDeprecation(DeprecationNotice notice) {
                    throw new IllegalStateException("boom");
                }
            }).build();

            assertThat(client.usage().plan()).isEqualTo("FREE");
        }
    }

    @Test
    void theRememberedValuesAreBounded() {
        List<String> versions = new CopyOnWriteArrayList<>();
        try (ApiStub stub = new ApiStub().replying(r -> Reply.json(200, "{}")
                .with("X-Listing-Guard-Version", "v-" + System.nanoTime()))) {
            ListingGuardClient client = stub.builder().listener(new ListingGuardListener() {
                @Override
                public void onServerVersion(ServerVersionNotice notice) {
                    versions.add(notice.serverVersion());
                }
            }).build();

            for (int i = 0; i < Notices.MAX_REMEMBERED + 10; i++) {
                client.send(RawRequest.usage());
            }

            assertThat(versions).hasSize(Notices.MAX_REMEMBERED);
        }
    }
}
