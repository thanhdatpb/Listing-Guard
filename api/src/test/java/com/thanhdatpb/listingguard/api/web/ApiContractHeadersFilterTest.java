package com.thanhdatpb.listingguard.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.web.ApiDeprecationProperties.Endpoint;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The filter of ADR-022 alone: the version header always, the deprecation headers for a configured operation only. */
class ApiContractHeadersFilterTest {

    private final ApiDeprecations deprecations = ApiDeprecations.of(List.of(
            new Endpoint("GET", "/api/v1/checks/{id}", "2027-01-01", "2027-07-01", "https://example.com/notes")));
    private final ApiContractHeadersFilter filter = new ApiContractHeadersFilter(new ServerVersion("0.8.0"), deprecations);

    private MockHttpServletResponse run(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).as("the request goes on").isSameAs(request);
        return response;
    }

    @Test
    void everyResponseGetsTheVersion() throws Exception {
        MockHttpServletResponse response = run("POST", "/api/v1/checks");

        assertThat(response.getHeader(ApiContractHeadersFilter.VERSION_HEADER)).isEqualTo("0.8.0");
        assertThat(response.getHeader(ApiContractHeadersFilter.DEPRECATION_HEADER)).isNull();
        assertThat(response.getHeader(ApiContractHeadersFilter.SUNSET_HEADER)).isNull();
        assertThat(response.getHeader("Link")).isNull();
    }

    @Test
    void aDeprecatedOperationGetsDeprecationSunsetAndLink() throws Exception {
        MockHttpServletResponse response = run("GET", "/api/v1/checks/0d7f2c1d-9a11-4a58-9c6e-7f0c2f3e6a52");

        assertThat(response.getHeader(ApiContractHeadersFilter.VERSION_HEADER)).isEqualTo("0.8.0");
        assertThat(response.getHeader(ApiContractHeadersFilter.DEPRECATION_HEADER)).isEqualTo("@1798761600");
        assertThat(response.getHeader(ApiContractHeadersFilter.SUNSET_HEADER)).isEqualTo("Thu, 01 Jul 2027 00:00:00 GMT");
        assertThat(response.getHeader("Link")).isEqualTo("<https://example.com/notes>; rel=\"deprecation\"; type=\"text/html\"");
    }

    @Test
    void theServerVersionIsARelease() {
        assertThat(ServerVersion.fromBuild(null).value()).isEqualTo(ServerVersion.DEV);
        assertThat(ServerVersion.fromBuild(" ").value()).isEqualTo(ServerVersion.DEV);
        assertThat(ServerVersion.fromBuild("dev").value()).isEqualTo("dev");
        assertThat(ServerVersion.fromBuild("0.8.0").value()).isEqualTo("0.8.0");
        assertThat(ServerVersion.fromBuild("v0.8.0").value()).as("the v of the tag is dropped").isEqualTo("0.8.0");
        assertThat(ServerVersion.fromBuild("1.0.0-rc.1").value()).isEqualTo("1.0.0-rc.1");
        assertThatThrownBy(() -> ServerVersion.fromBuild("latest")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Dlisting-guard.release");
        assertThatThrownBy(() -> ServerVersion.fromBuild("0.8")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ServerVersion.fromBuild("0.8.0\r\nX-Other: 1")).as("never a second header line")
                .isInstanceOf(IllegalStateException.class);
    }
}
