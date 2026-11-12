package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.client.Conditional;
import com.thanhdatpb.listingguard.client.ListingGuardApiException;
import com.thanhdatpb.listingguard.client.ListingGuardClient;
import com.thanhdatpb.listingguard.client.ListingGuardListener;
import com.thanhdatpb.listingguard.client.NotConfiguredException;
import com.thanhdatpb.listingguard.client.Operation;
import com.thanhdatpb.listingguard.client.RawRequest;
import com.thanhdatpb.listingguard.client.RawResponse;
import com.thanhdatpb.listingguard.client.RetryNotice;
import com.thanhdatpb.listingguard.client.RetryPolicy;
import com.thanhdatpb.listingguard.client.ValidationException;
import com.thanhdatpb.listingguard.client.model.CatalogState;
import com.thanhdatpb.listingguard.client.model.Capabilities;
import com.thanhdatpb.listingguard.client.model.CheckRequest;
import com.thanhdatpb.listingguard.client.model.CheckResult;
import com.thanhdatpb.listingguard.client.model.CheckStatus;
import com.thanhdatpb.listingguard.client.model.Completeness;
import com.thanhdatpb.listingguard.client.model.PersonalizationScreeningRequest;
import com.thanhdatpb.listingguard.client.model.PersonalizationScreeningResult;
import com.thanhdatpb.listingguard.client.model.PersonalizationValue;
import com.thanhdatpb.listingguard.client.model.RiskLevel;
import com.thanhdatpb.listingguard.client.model.RulesetLimits;
import com.thanhdatpb.listingguard.client.model.Severity;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckRequest;
import com.thanhdatpb.listingguard.client.model.TrademarkScanStatus;
import com.thanhdatpb.listingguard.client.model.Usage;
import com.thanhdatpb.listingguard.client.model.VariationCheckRequest;
import com.thanhdatpb.listingguard.client.model.VariationCheckResult;
import com.thanhdatpb.listingguard.client.model.VariationChild;
import com.thanhdatpb.listingguard.client.model.VariationParent;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The Java client SDK against the real API on a random port (ADR-023): PostgreSQL in Testcontainers and the test clients of
 * {@link TestcontainersConfig}, a default deployment (no trademark catalog, plans and fixes off). Every typed method, a 304, the
 * problem types of a bad request, an unknown marketplace and a bad key, and that the answers carry no member the SDK does not
 * map.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class SdkIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();

    @LocalServerPort
    int port;

    private ListingGuardClient client(String key) {
        return ListingGuardClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKey(key)
                .timeout(Duration.ofSeconds(20))
                .userAgent("sdk-integration-test/1")
                .build();
    }

    private static Map<String, Object> etsyListing(String title) {
        return Map.of(
                "title", title,
                "description", "A hand thrown stoneware mug, glazed in blue, holds 350 ml.",
                "tags", List.of("stoneware mug", "blue mug", "coffee mug"),
                "price", Map.of("amount", 2800, "divisor", 100, "currency_code", "USD"),
                "quantity", 4,
                "who_made", "i_did",
                "when_made", "made_to_order",
                "taxonomy_id", 1063);
    }

    /** The JSON member names of a record, as the SDK reads them. */
    private static Set<String> names(Class<?> record) throws Exception {
        Set<String> out = new HashSet<>();
        for (RecordComponent c : record.getRecordComponents()) {
            var renamed = record.getDeclaredField(c.getName())
                    .getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            out.add(renamed != null ? renamed.value() : c.getName());
        }
        return out;
    }

    /** Every top-level member of the API's answer is one the SDK maps (the SDK would ignore others silently). */
    private static void assertNoUnmappedMember(RawResponse response, Class<?> record) throws Exception {
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = M.readTree(response.body());
        Set<String> members = new HashSet<>();
        body.fieldNames().forEachRemaining(members::add);
        assertThat(names(record)).as(record.getSimpleName()).containsAll(members);
    }

    @Test
    void aListingCheckIsStoredAndAnIdenticalOneIsCached() {
        try (ListingGuardClient client = client(ApiTestKeys.KEY_1)) {
            String title = "Blue stoneware mug " + UUID.randomUUID();

            CheckResult first = client.checks(CheckRequest.of("ETSY", etsyListing(title)));
            CheckResult second = client.checksAsync(CheckRequest.of("ETSY", etsyListing(title))).join();

            assertThat(first.status()).isEqualTo(CheckStatus.COMPLETED);
            assertThat(first.cached()).isFalse();
            assertThat(first.rulesetVersion()).startsWith("etsy-");
            assertThat(first.findings()).allSatisfy(f -> assertThat(f.severity()).isNotEqualTo(Severity.UNRECOGNIZED));
            assertThat(second.cached()).isTrue();
            assertThat(second.checkId()).isEqualTo(first.checkId());
        }
    }

    @Test
    void theTrademarkCheckOfADefaultDeploymentIsNotConfiguredAndIsNotRetried() {
        List<RetryNotice> retries = new CopyOnWriteArrayList<>();
        try (ListingGuardClient client = ListingGuardClient.builder().baseUrl("http://127.0.0.1:" + port)
                .apiKey(ApiTestKeys.KEY_1).retry(RetryPolicy.defaults()).listener(new ListingGuardListener() {
                    @Override
                    public void onRetry(RetryNotice notice) {
                        retries.add(notice);
                    }
                }).build()) {
            Throwable thrown = catchThrowable(() -> client.trademarkChecks(
                    TrademarkCheckRequest.of("ETSY", etsyListing("Zorvex mug")).withProductType("mug")));

            assertThat(thrown).isInstanceOfSatisfying(NotConfiguredException.class, e -> {
                assertThat(e.status()).isEqualTo(503);
                assertThat(e.code()).isEqualTo("trademark-not-configured");
                assertThat(e.requestId()).isNotBlank();
            });
            assertThat(retries).isEmpty();
        }
    }

    @Test
    void aVariationFamilyIsChecked() throws Exception {
        try (ListingGuardClient client = client(ApiTestKeys.KEY_1)) {
            String sku = "TEE-" + UUID.randomUUID();
            VariationCheckRequest request = VariationCheckRequest.amazon(
                    new VariationParent(sku, "SHIRT", "Color-Size", "Retro Sunset Tee"),
                    List.of(new VariationChild(sku + "-RED-S", sku, "Retro Sunset Tee - Red - S",
                                    Map.of("color", "Red", "size", "S")),
                            new VariationChild(sku + "-RED-S", sku, "Retro Sunset Tee - Red - S",
                                    Map.of("color", "Red", "size", "S"))));

            VariationCheckResult result = client.variationChecks(request);

            assertThat(result.marketplace()).isEqualTo("AMAZON");
            assertThat(result.completeness()).isIn(Completeness.COMPLETE, Completeness.INCOMPLETE);
            assertThat(result.findings()).extracting(f -> f.ruleId()).contains("duplicate_sku");
            assertThat(result.disclaimer()).isNotBlank();
            assertNoUnmappedMember(client.send(RawRequest.post(Operation.VARIATION_CHECKS,
                    "{\"marketplace\":\"ETSY\",\"variation_properties\":[\"Size\"],\"children\":[{\"sku\":\"A\","
                            + "\"attributes\":{\"Size\":\"S\"}}]}")), VariationCheckResult.class);
        }
    }

    @Test
    void aPersonalizationScreeningWithoutACatalogIsIncompleteWithAnUnknownRisk() throws Exception {
        try (ListingGuardClient client = client(ApiTestKeys.KEY_1)) {
            PersonalizationScreeningResult result = client.personalizationScreenings(PersonalizationScreeningRequest.of(
                    "ETSY", List.of(PersonalizationValue.of("name", "  Dat  "), PersonalizationValue.text("1234"))));

            assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
            assertThat(result.trademark().status()).isEqualTo(TrademarkScanStatus.NOT_CONFIGURED);
            assertThat(result.trademark().riskLevel()).isEqualTo(RiskLevel.UNKNOWN);
            assertThat(result.findings()).extracting(f -> f.ruleId()).contains("whitespace_issue");
            assertNoUnmappedMember(client.send(RawRequest.post(Operation.PERSONALIZATION_SCREENINGS,
                    "{\"marketplace\":\"ETSY\",\"values\":[{\"text\":\"Ann\"}]}")), PersonalizationScreeningResult.class);
        }
    }

    @Test
    void theLimitsTheCapabilitiesWithA304AndTheUsageAreRead() throws Exception {
        try (ListingGuardClient client = client(ApiTestKeys.KEY_1)) {
            RulesetLimits limits = client.rulesetLimits("ETSY");
            Conditional<Capabilities> first = client.capabilities(null);
            Conditional<Capabilities> again = client.capabilities(first.etag());
            Usage usage = client.usage();

            assertThat(limits.marketplace()).isEqualTo("ETSY");
            assertThat(limits.limits()).isNotEmpty().allSatisfy(l -> assertThat(l.kind()).isNotNull());
            assertThat(first.notModified()).isFalse();
            assertThat(first.etag()).isNotBlank();
            assertThat(first.value().trademarkCatalog().state()).isEqualTo(CatalogState.DISABLED);
            assertThat(first.value().packs()).extracting(p -> p.id()).contains("listing-rules", "trademark-risk");
            assertThat(again.notModified()).isTrue();
            assertThat(again.value()).isNull();
            assertThat(usage.enforced()).isFalse();

            assertNoUnmappedMember(client.send(RawRequest.rulesetLimits("AMAZON", null)), RulesetLimits.class);
            assertNoUnmappedMember(client.send(RawRequest.capabilities()), Capabilities.class);
            assertNoUnmappedMember(client.send(RawRequest.usage()), Usage.class);
        }
    }

    @Test
    void aCheckAnswerHasNoMemberTheSdkDoesNotMap() throws Exception {
        try (ListingGuardClient client = client(ApiTestKeys.KEY_1)) {
            String body = M.writeValueAsString(Map.of("marketplace", "ETSY", "listing",
                    etsyListing("Raw mug " + UUID.randomUUID())));

            assertNoUnmappedMember(client.send(RawRequest.post(Operation.CHECKS, body)), CheckResult.class);
        }
    }

    @Test
    void theProblemsOfTheRealApiBecomeTheirExceptions() {
        try (ListingGuardClient client = client(ApiTestKeys.KEY_1);
                ListingGuardClient wrongKey = client("not-a-real-key-0123456789abcdef")) {
            Throwable unknownMarketplace = catchThrowable(() -> client.checks(CheckRequest.of("NOPE", etsyListing("x"))));
            Throwable unauthorized = catchThrowable(wrongKey::usage);
            Throwable noRuleset = catchThrowable(() -> client.rulesetLimits("NOPE"));

            assertThat(unknownMarketplace).isExactlyInstanceOf(ListingGuardApiException.class);
            assertThat(((ListingGuardApiException) unknownMarketplace).code()).isEqualTo("unknown-marketplace");
            assertThat(((ListingGuardApiException) unknownMarketplace).status()).isEqualTo(400);
            assertThat(unauthorized).isExactlyInstanceOf(ListingGuardApiException.class);
            assertThat(((ListingGuardApiException) unauthorized).code()).isEqualTo("unauthorized");
            assertThat(((ListingGuardApiException) unauthorized).getMessage()).doesNotContain("not-a-real-key");
            assertThat(noRuleset).isInstanceOfSatisfying(ListingGuardApiException.class,
                    e -> assertThat(e.code()).isIn("ruleset-not-found", "unknown-marketplace"));
        }
    }

    @Test
    void aBodyWithoutAListingIsAValidationErrorWithItsFields() {
        try (ListingGuardClient client = client(ApiTestKeys.KEY_1)) {
            RawResponse raw = client.send(RawRequest.post(Operation.CHECKS, "{\"marketplace\":\"ETSY\"}"));
            assertThat(raw.status()).isEqualTo(400);

            Throwable thrown = catchThrowable(() -> client.trademarkChecks(TrademarkCheckRequest.ofJson("ETSY", "{}")
                    .withProductType("x".repeat(65))));

            assertThat(thrown).isInstanceOfSatisfying(ValidationException.class, e -> {
                assertThat(e.code()).isEqualTo("invalid-request");
                assertThat(e.errors()).isNotEmpty();
            });
        }
    }
}
