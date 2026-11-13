package com.thanhdatpb.listingguard.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The paths that may carry a larger {@code multipart/form-data POST}: the image checks (ADR-008) and the two pages of the console's
 * bulk check (ADR-029), each up to its own limit. Nothing else is: not another path (a path is whole, not a prefix), not another
 * method, not another content type.
 */
class BodyLimitFilterUploadMapTest {

    private static final long ORDINARY = 1_000;
    private static final String IMAGES = "/api/v1/image-checks";
    private static final String PREPARE = "/console/bulk/prepare";
    private static final String RUN = "/console/bulk/run";

    private final BodyLimitFilter filter = new BodyLimitFilter(ORDINARY, new ObjectMapper(),
            Map.of(IMAGES, 50_000L, PREPARE, 20_000L, RUN, 30_000L));

    private int statusOf(String method, String path, String contentType, int bytes) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContentType(contentType);
        request.setContent(new byte[bytes]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() == null ? response.getStatus() : 200;
    }

    @Test
    void eachPathHasItsOwnLimitForAMultipartPost() throws Exception {
        String multipart = "multipart/form-data; boundary=x";

        assertThat(statusOf("POST", PREPARE, multipart, 15_000)).isEqualTo(200);
        assertThat(statusOf("POST", PREPARE, multipart, 25_000)).isEqualTo(413);
        assertThat(statusOf("POST", RUN, multipart, 25_000)).isEqualTo(200);
        assertThat(statusOf("POST", RUN, multipart, 35_000)).isEqualTo(413);
        assertThat(statusOf("POST", IMAGES, multipart, 40_000)).isEqualTo(200);
    }

    @Test
    void everythingElseKeepsTheOrdinaryLimit() throws Exception {
        String multipart = "multipart/form-data; boundary=x";

        assertThat(statusOf("POST", PREPARE, "application/x-www-form-urlencoded", 5_000)).as("not multipart").isEqualTo(413);
        assertThat(statusOf("POST", PREPARE + "/more", multipart, 5_000)).as("a longer path").isEqualTo(413);
        assertThat(statusOf("POST", "/console/bulk", multipart, 5_000)).as("a shorter path").isEqualTo(413);
        assertThat(statusOf("POST", "/console/check", multipart, 5_000)).as("another page").isEqualTo(413);
        assertThat(statusOf("PUT", RUN, multipart, 5_000)).as("another method").isEqualTo(413);
        assertThat(statusOf("POST", RUN, null, 5_000)).as("no content type").isEqualTo(413);
        assertThat(statusOf("POST", "/console/check", "application/x-www-form-urlencoded", 500)).isEqualTo(200);
    }

    @Test
    void anEmptyMapLeavesNoException() throws Exception {
        BodyLimitFilter plain = new BodyLimitFilter(ORDINARY, new ObjectMapper(), Map.of());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PREPARE);
        request.setContentType("multipart/form-data; boundary=x");
        request.setContent(new byte[5_000]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        plain.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(413);
    }
}
