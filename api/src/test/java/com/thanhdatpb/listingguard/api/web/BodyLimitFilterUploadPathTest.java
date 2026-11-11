package com.thanhdatpb.listingguard.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The one exception to the ordinary body limit (ADR-008): a {@code multipart/form-data POST} to the image path may be larger,
 * up to its own limit. Nothing else is: not another path, not another method, not another content type.
 */
class BodyLimitFilterUploadPathTest {

    private static final String PATH = "/api/v1/image-checks";
    private static final long ORDINARY = 1_000;
    private static final long UPLOAD = 50_000;

    private final BodyLimitFilter filter = new BodyLimitFilter(ORDINARY, new ObjectMapper(), PATH, UPLOAD);

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
    void aMultipartPostToTheImagePathMayBeAsLargeAsTheUploadLimit() throws Exception {
        assertThat(statusOf("POST", PATH, "multipart/form-data; boundary=x", 40_000)).isEqualTo(200);
        assertThat(statusOf("POST", PATH, "multipart/form-data; boundary=x", 60_000)).isEqualTo(413);
    }

    @Test
    void everythingElseKeepsTheOrdinaryLimit() throws Exception {
        assertThat(statusOf("POST", PATH, "application/json", 40_000)).as("not multipart").isEqualTo(413);
        assertThat(statusOf("POST", "/api/v1/checks", "multipart/form-data; boundary=x", 40_000)).as("another path").isEqualTo(413);
        assertThat(statusOf("PUT", PATH, "multipart/form-data; boundary=x", 40_000)).as("another method").isEqualTo(413);
        assertThat(statusOf("POST", PATH, null, 40_000)).as("no content type").isEqualTo(413);
        assertThat(statusOf("POST", "/api/v1/checks", "application/json", 500)).isEqualTo(200);
    }

    @Test
    void withoutAnUploadPathTheLimitIsTheOrdinaryOneEverywhere() throws Exception {
        BodyLimitFilter plain = new BodyLimitFilter(ORDINARY, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContentType("multipart/form-data; boundary=x");
        request.setContent(new byte[40_000]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        plain.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(413);
    }
}
