package com.thanhdatpb.listingguard.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.client.ApiStub.Reply;
import com.thanhdatpb.listingguard.client.model.CatalogState;
import com.thanhdatpb.listingguard.client.model.CheckRequest;
import com.thanhdatpb.listingguard.client.model.CheckResult;
import com.thanhdatpb.listingguard.client.model.CheckStatus;
import com.thanhdatpb.listingguard.client.model.Completeness;
import com.thanhdatpb.listingguard.client.model.LimitKind;
import com.thanhdatpb.listingguard.client.model.LimitUnit;
import com.thanhdatpb.listingguard.client.model.MatchType;
import com.thanhdatpb.listingguard.client.model.PersonalizationScreeningRequest;
import com.thanhdatpb.listingguard.client.model.PersonalizationScreeningResult;
import com.thanhdatpb.listingguard.client.model.PersonalizationValue;
import com.thanhdatpb.listingguard.client.model.RiskLevel;
import com.thanhdatpb.listingguard.client.model.RulesetLimits;
import com.thanhdatpb.listingguard.client.model.Severity;
import com.thanhdatpb.listingguard.client.model.SourceStatus;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckRequest;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckResult;
import com.thanhdatpb.listingguard.client.model.TrademarkScanStatus;
import com.thanhdatpb.listingguard.client.model.Usage;
import com.thanhdatpb.listingguard.client.model.VariationCheckRequest;
import com.thanhdatpb.listingguard.client.model.VariationCheckResult;
import com.thanhdatpb.listingguard.client.model.VariationChild;
import com.thanhdatpb.listingguard.client.model.VariationParent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Each typed call: the method, the path, the headers, the body as sent and the typed result. */
class ClientEndpointsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    static final String CHECK_RESULT = """
            {"checkId":"7d1f3a52-1b2c-4c7e-9a40-2f0e3c9b8a11","marketplace":"ETSY","rulesetVersion":"etsy-2026-12",
             "analysisVersion":"a1","contentHash":"h1","status":"COMPLETED","cached":false,
             "createdAt":"2026-10-28T09:15:30.123456Z","summary":{"errors":1,"warnings":0,"infos":0},
             "findings":[{"ruleId":"tag_length","source":"RULE","severity":"ERROR","field":"tags[0]","value":"a very long tag",
               "message":"Tag dài 21 ký tự.","suggestion":"Rút gọn.","fix":null}],
             "fixesComputed":null,"readiness":null}""";

    static final String TRADEMARK_RESULT = """
            {"checkId":"0b9f6c1e-2d3a-4b5c-8d7e-6f5a4b3c2d1e","marketplace":"ETSY","rulesetVersion":"trademark-risk-2026-11b",
             "catalogVersion":"demo-2026-11-0123","analysisVersion":"t1","cached":true,"completeness":"COMPLETE",
             "riskLevel":"MEDIUM","failedRuleIds":[],"disclaimer":"Chỉ là cảnh báo tham khảo.",
             "createdAt":"2026-10-30T09:15:30.123456Z","expiresAt":"2026-11-29T09:15:30.123456Z","productType":"mug",
             "findings":[{"ruleId":"catalog_exact","risk":"MEDIUM","field":"title","start":0,"end":6,"snippet":"Zorvex",
               "mark":"ZORVEX","matchType":"EXACT","score":1.0,"insideColorPhrase":false,"otherRuleIds":[],
               "sourceIds":["uspto:90000001"],"sourceLinks":[{"sourceId":"uspto:90000001","label":"USPTO TSDR",
               "url":"https://tsdr.example/90000001"}],"riskAdjustments":[{"reason":"product_type_classes","from":"HIGH",
               "to":"MEDIUM","note":"Nhóm khác."}],"message":"Khớp nhãn.","suggestion":"Kiểm tra.",
               "tsdrStatuses":[{"sourceId":"uspto:90000001","statusCode":"602","statusDescription":"ABANDONED",
               "statusClass":"DEAD","statusDate":"2024-05-06","checkedAt":"2026-11-12T11:00:00Z"}]}]}""";

    static final String VARIATION_RESULT = """
            {"checkId":"5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d","marketplace":"AMAZON","rulesetVersion":"variation-checks-2026-11",
             "analysisVersion":"v1","cached":false,"completeness":"COMPLETE","failedRuleIds":[],"disclaimer":"Tham khảo.",
             "createdAt":"2026-10-30T09:15:30Z","expiresAt":"2026-11-29T09:15:30Z","summary":{"errors":0,"warnings":1,"infos":0},
             "findings":[{"ruleId":"duplicate_sku","source":"RULE","severity":"WARNING","field":"children[1].sku","value":"TEE-1",
               "message":"Trùng SKU.","suggestion":"Đổi SKU.","fix":null}]}""";

    static final String PERSONALIZATION_RESULT = """
            {"checkId":"1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d","marketplace":"ETSY",
             "rulesetVersion":"personalization-screening-2026-11","analysisVersion":"p1","printProfile":"latin_basic",
             "cached":false,"completeness":"INCOMPLETE","failedRuleIds":[],"disclaimer":"Tham khảo.",
             "createdAt":"2026-10-30T09:15:30Z","expiresAt":"2026-11-29T09:15:30Z","summary":{"errors":0,"warnings":1,"infos":0},
             "findings":[{"ruleId":"whitespace_issue","severity":"WARNING","sourceStatus":"UNVERIFIED","kind":"leading_space",
               "field":"values[0]","label":"name","start":0,"end":2,"value":"␠␠","message":"Có khoảng trắng.",
               "suggestion":"Hỏi lại người mua."}],
             "trademark":{"status":"NOT_CONFIGURED","riskLevel":"UNKNOWN","rulesetVersion":null,"catalogVersion":null,
               "failedRuleIds":[],"findings":[]}}""";

    static final String LIMITS_RESULT = """
            {"marketplace":"AMAZON","productType":"SHIRT","rulesetVersion":"amazon-2026-12c","limits":[
              {"field":"title","ruleId":"title_length","kind":"MAX_LENGTH","value":75,"unit":"CODE_POINTS","severity":"WARNING",
               "sourceStatus":"SECONDARY","enabled":true}]}""";

    static final String CAPABILITIES = """
            {"packs":[{"id":"listing-rules","available":true,"rulesetVersions":{"AMAZON":"amazon-2026-12c","ETSY":"etsy-2026-12"}},
              {"id":"trademark-risk","available":false,"rulesetVersions":{"ETSY":"trademark-risk-2026-11b"}}],
             "trademarkCatalog":{"state":"DISABLED","demo":false},"plans":{"enforced":false},"fixes":{"enabled":false}}""";

    static final String USAGE = """
            {"plan":"SELLER","packs":["batch","listing-rules"],"enforced":true,"monthlyQuota":1000,"used":12,"remaining":988,
             "periodStart":"2026-11-01","resetAt":"2026-12-01T00:00:00Z"}""";

    private static void assertCommonHeaders(ApiStub.Recorded r) {
        assertThat(r.header("X-API-Key")).isEqualTo(ApiStub.KEY);
        assertThat(r.header("X-Request-Id")).matches(UUID_FORMAT);
        assertThat(r.header("Accept")).isEqualTo("application/json");
        assertThat(r.header("User-Agent")).startsWith("listing-guard-client/");
    }

    @Test
    void checksPostsTheEnvelopeAndReadsTheResult() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, CHECK_RESULT))) {
            Map<String, Object> listing = new LinkedHashMap<>();
            listing.put("title", "  Handmade mug  ");
            listing.put("tags", List.of("a very long tag", "mug"));
            listing.put("price", Map.of("amount", 2500, "divisor", 100, "currency_code", "USD"));

            CheckResult result = stub.client().checks(CheckRequest.of("ETSY", listing));

            ApiStub.Recorded r = stub.only();
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.target()).isEqualTo("/api/v1/checks");
            assertThat(r.header("Content-Type")).isEqualTo("application/json");
            assertCommonHeaders(r);
            JsonNode body = M.readTree(r.body());
            assertThat(body.get("marketplace").asText()).isEqualTo("ETSY");
            assertThat(body.at("/listing/title").asText()).as("not trimmed by the SDK").isEqualTo("  Handmade mug  ");
            assertThat(body.at("/listing/price/currency_code").asText()).isEqualTo("USD");

            assertThat(result.checkId()).isEqualTo(UUID.fromString("7d1f3a52-1b2c-4c7e-9a40-2f0e3c9b8a11"));
            assertThat(result.status()).isEqualTo(CheckStatus.COMPLETED);
            assertThat(result.createdAt()).isEqualTo(Instant.parse("2026-10-28T09:15:30.123456Z"));
            assertThat(result.summary().errors()).isEqualTo(1);
            assertThat(result.findings()).singleElement().satisfies(f -> {
                assertThat(f.ruleId()).isEqualTo("tag_length");
                assertThat(f.severity()).isEqualTo(Severity.ERROR);
                assertThat(f.value()).isEqualTo("a very long tag");
                assertThat(f.fix()).isNull();
            });
            assertThat(result.fixesComputed()).isNull();
            assertThat(result.readiness()).isNull();
        }
    }

    @Test
    void aListingGivenAsJsonTextIsSentAsTheSameObjectWithExactNumbers() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, CHECK_RESULT))) {
            String json = "{\"title\":\"Mug\",\"price\":12.50,\"quantity\":3}";

            stub.client().checks(CheckRequest.ofJson("ETSY", json));

            String sent = stub.only().body();
            assertThat(sent).contains("\"listing\":{\"title\":\"Mug\",\"price\":12.50,\"quantity\":3}");
        }
    }

    @Test
    void trademarkChecksSendsTheProductTypeUnchangedAndReadsEveryMember() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, TRADEMARK_RESULT))) {
            TrademarkCheckResult result = stub.client().trademarkChecks(
                    TrademarkCheckRequest.of("ETSY", Map.of("title", "Zorvex mug")).withProductType("mug"));

            ApiStub.Recorded r = stub.only();
            assertThat(r.target()).isEqualTo("/api/v1/trademark-checks");
            assertCommonHeaders(r);
            JsonNode body = M.readTree(r.body());
            assertThat(body.get("product_type").asText()).isEqualTo("mug");
            assertThat(body.at("/listing/title").asText()).isEqualTo("Zorvex mug");

            assertThat(result.cached()).isTrue();
            assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
            assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
            assertThat(result.productType()).isEqualTo("mug");
            assertThat(result.disclaimer()).isEqualTo("Chỉ là cảnh báo tham khảo.");
            assertThat(result.findings()).singleElement().satisfies(f -> {
                assertThat(f.matchType()).isEqualTo(MatchType.EXACT);
                assertThat(f.score()).isEqualTo(1.0);
                assertThat(f.sourceLinks()).singleElement().satisfies(l -> assertThat(l.label()).isEqualTo("USPTO TSDR"));
                assertThat(f.tsdrStatuses()).singleElement().satisfies(s -> {
                    assertThat(s.sourceId()).isEqualTo("uspto:90000001");
                    assertThat(s.statusClass()).isEqualTo(com.thanhdatpb.listingguard.client.model.TsdrStatusClass.DEAD);
                    assertThat(s.statusDate()).isEqualTo(java.time.LocalDate.of(2024, 5, 6));
                    assertThat(s.checkedAt()).isEqualTo(java.time.Instant.parse("2026-11-12T11:00:00Z"));
                });
                assertThat(f.riskAdjustments()).singleElement().satisfies(a -> {
                    assertThat(a.from()).isEqualTo(RiskLevel.HIGH);
                    assertThat(a.to()).isEqualTo(RiskLevel.MEDIUM);
                });
            });
        }
    }

    @Test
    void aTrademarkCheckWithoutAProductTypeSendsNoProductTypeMember() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, TRADEMARK_RESULT))) {
            stub.client().trademarkChecks(TrademarkCheckRequest.of("ETSY", Map.of("title", "x")));

            assertThat(M.readTree(stub.only().body()).has("product_type")).isFalse();
        }
    }

    @Test
    void variationChecksSendsTheFamilyAtTheTopLevel() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, VARIATION_RESULT))) {
            VariationCheckRequest request = VariationCheckRequest.amazon(
                    new VariationParent("TEE-P", "SHIRT", "Color-Size", "Retro Tee"),
                    List.of(new VariationChild("TEE-1", "TEE-P", "Retro Tee - Red - S", Map.of("color", "Red", "size", "S")),
                            new VariationChild("TEE-1", "TEE-P", null, Map.of("color", "Red", "size", "M"))));

            VariationCheckResult result = stub.client().variationChecks(request);

            ApiStub.Recorded r = stub.only();
            assertThat(r.target()).isEqualTo("/api/v1/variation-checks");
            JsonNode body = M.readTree(r.body());
            assertThat(body.get("marketplace").asText()).isEqualTo("AMAZON");
            assertThat(body.at("/parent/product_type").asText()).isEqualTo("SHIRT");
            assertThat(body.at("/parent/variation_theme").asText()).isEqualTo("Color-Size");
            assertThat(body.at("/children/0/parent_sku").asText()).isEqualTo("TEE-P");
            assertThat(body.at("/children/1").has("title")).as("null members are left out").isFalse();
            assertThat(body.has("variation_properties")).isFalse();

            assertThat(result.findings()).singleElement().satisfies(f -> assertThat(f.ruleId()).isEqualTo("duplicate_sku"));
            assertThat(result.summary().warnings()).isEqualTo(1);
        }
    }

    @Test
    void anEtsyVariationRequestSendsTheVariationProperties() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, VARIATION_RESULT))) {
            stub.client().variationChecks(VariationCheckRequest.etsy(List.of("Primary color", "Size"),
                    List.of(VariationChild.of("A", Map.of("Primary color", "Blue")))));

            JsonNode body = M.readTree(stub.only().body());
            assertThat(body.get("marketplace").asText()).isEqualTo("ETSY");
            assertThat(body.get("variation_properties").get(1).asText()).isEqualTo("Size");
            assertThat(body.has("parent")).isFalse();
        }
    }

    @Test
    void personalizationScreeningsSendsTheBuyersTextExactlyAsTyped() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, PERSONALIZATION_RESULT))) {
            PersonalizationScreeningRequest request = PersonalizationScreeningRequest.of("ETSY",
                            List.of(PersonalizationValue.of("name", "  Đạt  "), PersonalizationValue.text("a  b\t")))
                    .withPrintProfile("latin_basic").withProductType("jersey");

            PersonalizationScreeningResult result = stub.client().personalizationScreenings(request);

            ApiStub.Recorded r = stub.only();
            assertThat(r.target()).isEqualTo("/api/v1/personalization-screenings");
            JsonNode body = M.readTree(r.body());
            assertThat(body.at("/values/0/text").asText()).isEqualTo("  Đạt  ");
            assertThat(body.at("/values/1/text").asText()).isEqualTo("a  b\t");
            assertThat(body.at("/values/1").has("label")).isFalse();
            assertThat(body.get("print_profile").asText()).isEqualTo("latin_basic");
            assertThat(body.get("product_type").asText()).isEqualTo("jersey");

            assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
            assertThat(result.findings()).singleElement().satisfies(f -> {
                assertThat(f.sourceStatus()).isEqualTo(SourceStatus.UNVERIFIED);
                assertThat(f.label()).isEqualTo("name");
            });
            assertThat(result.trademark().status()).isEqualTo(TrademarkScanStatus.NOT_CONFIGURED);
            assertThat(result.trademark().riskLevel()).isEqualTo(RiskLevel.UNKNOWN);
        }
    }

    @Test
    void rulesetLimitsIsAGetWithTheProductTypeInTheQuery() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, LIMITS_RESULT))) {
            RulesetLimits limits = stub.client().rulesetLimits("AMAZON", "SHIRT");

            ApiStub.Recorded r = stub.only();
            assertThat(r.method()).isEqualTo("GET");
            assertThat(r.target()).isEqualTo("/api/v1/rulesets/AMAZON/limits?productType=SHIRT");
            assertThat(r.body()).isEmpty();
            assertThat(r.header("Content-Type")).isNull();
            assertCommonHeaders(r);
            assertThat(limits.rulesetVersion()).isEqualTo("amazon-2026-12c");
            assertThat(limits.limits()).singleElement().satisfies(l -> {
                assertThat(l.kind()).isEqualTo(LimitKind.MAX_LENGTH);
                assertThat(l.unit()).isEqualTo(LimitUnit.CODE_POINTS);
                assertThat(l.value()).isEqualTo(75);
                assertThat(l.enabled()).isTrue();
            });
        }
    }

    @Test
    void rulesetLimitsWithoutAProductTypeHasNoQuery() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, LIMITS_RESULT))) {
            stub.client().rulesetLimits("ETSY");

            assertThat(stub.only().target()).isEqualTo("/api/v1/rulesets/ETSY/limits");
        }
    }

    @Test
    void capabilitiesAndUsageAreReadAsTypes() throws Exception {
        try (ApiStub stub = new ApiStub().replying(r -> Reply.json(200, r.path().endsWith("usage") ? USAGE : CAPABILITIES))) {
            ListingGuardClient client = stub.client();

            var capabilities = client.capabilities();
            Usage usage = client.usage();

            assertThat(stub.requests()).extracting(ApiStub.Recorded::target)
                    .containsExactly("/api/v1/capabilities", "/api/v1/usage");
            assertThat(capabilities.trademarkCatalog().state()).isEqualTo(CatalogState.DISABLED);
            assertThat(capabilities.packs()).extracting(p -> p.id()).containsExactly("listing-rules", "trademark-risk");
            assertThat(capabilities.packs().get(0).rulesetVersions()).containsEntry("ETSY", "etsy-2026-12");
            assertThat(capabilities.plans().enforced()).isFalse();
            assertThat(usage.plan()).isEqualTo("SELLER");
            assertThat(usage.monthlyQuota()).isEqualTo(1000);
            assertThat(usage.remaining()).isEqualTo(988L);
            assertThat(usage.periodStart()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(usage.resetAt()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        }
    }

    @Test
    void theAsyncFormsReturnTheSameResults() throws Exception {
        try (ApiStub stub = new ApiStub().replying(r -> Reply.json(200, switch (r.path()) {
            case "/api/v1/checks" -> CHECK_RESULT;
            case "/api/v1/trademark-checks" -> TRADEMARK_RESULT;
            case "/api/v1/variation-checks" -> VARIATION_RESULT;
            case "/api/v1/personalization-screenings" -> PERSONALIZATION_RESULT;
            case "/api/v1/capabilities" -> CAPABILITIES;
            case "/api/v1/usage" -> USAGE;
            default -> LIMITS_RESULT;
        }))) {
            ListingGuardClient client = stub.client();

            assertThat(client.checksAsync(CheckRequest.of("ETSY", Map.of())).get().rulesetVersion())
                    .isEqualTo("etsy-2026-12");
            assertThat(client.trademarkChecksAsync(TrademarkCheckRequest.of("ETSY", Map.of())).get().riskLevel())
                    .isEqualTo(RiskLevel.MEDIUM);
            assertThat(client.variationChecksAsync(VariationCheckRequest.etsy(List.of(), List.of())).get().marketplace())
                    .isEqualTo("AMAZON");
            assertThat(client.personalizationScreeningsAsync(PersonalizationScreeningRequest.of("ETSY", List.of()))
                    .get().printProfile()).isEqualTo("latin_basic");
            assertThat(client.rulesetLimitsAsync("AMAZON", null).get().limits()).hasSize(1);
            assertThat(client.capabilitiesAsync().get().fixes().enabled()).isFalse();
            assertThat(client.usageAsync().get().used()).isEqualTo(12);
            assertThat(stub.requests()).hasSize(7);
        }
    }

    @Test
    void theCallersUserAgentComesFirstAndAccessHeadersAreSentWhenConfigured() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, USAGE))) {
            ListingGuardClient client = stub.builder()
                    .userAgent("shop-backend/1.4")
                    .cloudflareAccess("access-id.access", "access-secret-123")
                    .build();

            client.usage();

            ApiStub.Recorded r = stub.only();
            assertThat(r.header("User-Agent")).startsWith("shop-backend/1.4 listing-guard-client/");
            assertThat(r.header("CF-Access-Client-Id")).isEqualTo("access-id.access");
            assertThat(r.header("CF-Access-Client-Secret")).isEqualTo("access-secret-123");
        }
    }

    @Test
    void withoutAccessConfiguredNoAccessHeaderIsSent() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, USAGE))) {
            stub.client().usage();

            assertThat(stub.only().header("CF-Access-Client-Id")).isNull();
            assertThat(stub.only().header("CF-Access-Client-Secret")).isNull();
        }
    }

    @Test
    void theRequestIdSupplierIsUsedWhenItGivesAValidIdAndReplacedOtherwise() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, USAGE))) {
            stub.builder().requestIdSupplier(() -> "trace-42").build().usage();
            stub.builder().requestIdSupplier(() -> "bad id!").build().usage();
            stub.builder().requestIdSupplier(() -> null).build().usage();

            assertThat(stub.requests().get(0).header("X-Request-Id")).isEqualTo("trace-42");
            assertThat(stub.requests().get(1).header("X-Request-Id")).matches(UUID_FORMAT);
            assertThat(stub.requests().get(2).header("X-Request-Id")).matches(UUID_FORMAT);
        }
    }

    @Test
    void aRawPostSendsTheBodyAsGivenAndReturnsAnyStatusWithoutThrowing() throws Exception {
        String problem = ApiStub.problem(400, "unknown-marketplace", "");
        try (ApiStub stub = new ApiStub().replying(Reply.problem(400, problem))) {
            String body = "{\"marketplace\":\"EBAY\",\"listing\":{\"title\":\"  Đạt  \"},\"extra\":1.50}";

            RawResponse response = stub.client().send(RawRequest.post(Operation.CHECKS, body).withRequestId("req-123"));

            ApiStub.Recorded r = stub.only();
            assertThat(r.body()).isEqualTo(body);
            assertThat(r.header("X-Request-Id")).isEqualTo("req-123");
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).isEqualTo(problem);
            assertThat(response.requestId()).isEqualTo("req-123");
            assertThat(response.isSuccess()).isFalse();
        }
    }

    @Test
    void aRawGetBuildsThePathFromTheOperation() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            ListingGuardClient client = stub.client();

            client.send(RawRequest.usage());
            client.send(RawRequest.capabilities());
            client.send(RawRequest.rulesetLimits("ETSY", ""));

            assertThat(stub.requests()).extracting(ApiStub.Recorded::target).containsExactly(
                    "/api/v1/usage", "/api/v1/capabilities", "/api/v1/rulesets/ETSY/limits");
            assertThat(stub.requests()).allSatisfy(r -> assertThat(r.method()).isEqualTo("GET"));
        }
    }
}
