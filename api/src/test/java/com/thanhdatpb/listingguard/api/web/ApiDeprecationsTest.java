package com.thanhdatpb.listingguard.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.web.ApiDeprecationProperties.Endpoint;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The deprecation settings of ADR-022 section 6: header values, matching, and every refusal at start-up. */
class ApiDeprecationsTest {

    private static final String GET_CHECK = "/api/v1/checks/{id}";
    private static final String LINK = "https://example.com/notes#deprecations";

    private static Endpoint endpoint(String method, String path, String deprecatedAt, String sunsetAt, String link) {
        return new Endpoint(method, path, deprecatedAt, sunsetAt, link);
    }

    @Test
    void aDateAndAnInstantBecomeTheStandardHeaderValues() {
        ApiDeprecations deprecations = ApiDeprecations.of(List.of(endpoint("GET", GET_CHECK, "2027-01-01", "2027-07-01T00:00:00Z", LINK)));
        ApiDeprecations.DeprecatedEndpoint endpoint = deprecations.endpoints().getFirst();

        assertThat(endpoint.deprecatedAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(endpoint.deprecationHeader()).as("RFC 9745: @ and epoch seconds").isEqualTo("@1798761600");
        assertThat(endpoint.sunsetHeader()).as("RFC 8594: an IMF-fixdate").contains("Thu, 01 Jul 2027 00:00:00 GMT");
        assertThat(endpoint.linkHeader()).contains("<" + LINK + ">; rel=\"deprecation\"; type=\"text/html\"");
    }

    @Test
    void sunsetAndLinkAreOptional() {
        ApiDeprecations.DeprecatedEndpoint endpoint = ApiDeprecations.of(List.of(endpoint("get", GET_CHECK, "2027-01-01T12:30:00+02:00",
                null, " "))).endpoints().getFirst();

        assertThat(endpoint.method()).isEqualTo("GET");
        assertThat(endpoint.deprecatedAt()).isEqualTo(Instant.parse("2027-01-01T10:30:00Z"));
        assertThat(endpoint.sunsetHeader()).isEmpty();
        assertThat(endpoint.linkHeader()).isEmpty();
    }

    @Test
    void aRequestMatchesByMethodAndPathTemplate() {
        ApiDeprecations deprecations = ApiDeprecations.of(List.of(endpoint("GET", GET_CHECK, "2027-01-01", null, null)));

        assertThat(deprecations.find("GET", "/api/v1/checks/0d7f2c1d-9a11-4a58-9c6e-7f0c2f3e6a52")).isPresent();
        assertThat(deprecations.find("get", "/api/v1/checks/abc")).isPresent();
        assertThat(deprecations.find("POST", "/api/v1/checks/abc")).as("another method is another operation").isEmpty();
        assertThat(deprecations.find("GET", "/api/v1/checks")).isEmpty();
        assertThat(deprecations.find("GET", "/api/v1/checks/abc/more")).isEmpty();
        assertThat(ApiDeprecations.none().find("GET", "/api/v1/checks/abc")).isEmpty();
    }

    @Test
    void theSunsetIsAtLeast180DaysAfterTheDeprecation() {
        assertThat(ApiDeprecations.MINIMUM_NOTICE.toDays()).isEqualTo(180);
        Instant deprecated = Instant.parse("2027-01-01T00:00:00Z");
        String exactly = deprecated.plus(ApiDeprecations.MINIMUM_NOTICE).atOffset(ZoneOffset.UTC).toString();
        String aDayShort = deprecated.plus(ApiDeprecations.MINIMUM_NOTICE).minusSeconds(86_400).atOffset(ZoneOffset.UTC).toString();

        assertThat(ApiDeprecations.of(List.of(endpoint("GET", GET_CHECK, "2027-01-01", exactly, null))).endpoints()).hasSize(1);
        assertThatThrownBy(() -> ApiDeprecations.of(List.of(endpoint("GET", GET_CHECK, "2027-01-01", aDayShort, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoints[0].sunset-at must be at least 180 days after deprecated-at");
    }

    @Test
    void everyWrongSettingIsNamedAndStopsTheStart() {
        assertThatThrownBy(() -> ApiDeprecations.of(List.of(
                endpoint("FETCH", GET_CHECK, "2027-01-01", null, null),
                endpoint("GET", "/health", "2027-01-01", null, null),
                endpoint("GET", GET_CHECK, null, "2027-12-01", null),
                endpoint("GET", GET_CHECK, "next year", null, null),
                endpoint("GET", GET_CHECK, "2027-01-01", null, "http://example.com/notes"),
                endpoint("GET", GET_CHECK, "2027-01-01", null, "https://example.com/a b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoints[0].method must be one of")
                .hasMessageContaining("endpoints[1].path must be an /api/v1 path")
                .hasMessageContaining("endpoints[2].deprecated-at is required")
                .hasMessageContaining("endpoints[3].deprecated-at must be a date")
                .hasMessageContaining("endpoints[4].link must be an absolute https address")
                .hasMessageContaining("endpoints[5].link must be an absolute https address");
    }

    @Test
    void anOperationNamedTwiceIsRefused() {
        assertThatThrownBy(() -> ApiDeprecations.of(List.of(endpoint("GET", GET_CHECK, "2027-01-01", null, null),
                endpoint("get", GET_CHECK, "2027-02-01", null, null))))
                .hasMessageContaining("endpoints[1] names GET /api/v1/checks/{id} a second time");
    }

    @Test
    void anEntryThatNamesNoOperationOfTheApplicationStopsTheStart() {
        ApiDeprecations deprecations = ApiDeprecations.of(List.of(endpoint("GET", GET_CHECK, "2027-01-01", null, null),
                endpoint("DELETE", "/api/v1/checks/{id}", "2027-01-01", null, null)));
        Set<String> operations = Set.of("GET /api/v1/checks/{id}", "POST /api/v1/checks");

        assertThat(deprecations.unknownOperations(operations)).containsExactly("DELETE /api/v1/checks/{id}");
        assertThatThrownBy(() -> ApiDeprecationStartupCheck.check(deprecations, operations))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("[DELETE /api/v1/checks/{id}]");
        ApiDeprecationStartupCheck.check(ApiDeprecations.of(List.of(endpoint("GET", GET_CHECK, "2027-01-01", null, null))), operations);
    }
}
