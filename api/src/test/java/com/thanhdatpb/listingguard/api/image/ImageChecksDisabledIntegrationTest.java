package com.thanhdatpb.listingguard.api.image;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.CHECKS;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.main;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.product;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The default (ADR-008): image checks are off. The endpoint answers {@code 503 image-checks-not-configured}, stores nothing,
 * and nothing else is affected; a GET of an id answers the ordinary 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ImageChecksDisabledIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ImageProperties properties;

    @Test
    void imageChecksAreOffByDefaultAndTheEndpointSaysSoWithoutStoringAnything() throws Exception {
        assertThat(properties.enabled()).isFalse();

        mvc.perform(request("AMAZON", List.of(main(product(300, 200, "PNG", 0)))).header(HEADER, KEY_1))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("image-checks-not-configured"))
                .andExpect(jsonPath("$.requestId").exists());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_check", Integer.class)).isZero();
    }

    @Test
    void whileOffNoMultipartBodyOverTheOrdinaryLimitIsRead() throws Exception {
        // the large upload limit exists only while the endpoint is on; a body declared larger than 256 KB is refused unread
        mvc.perform(request("AMAZON", List.of(main(new byte[10]))).header(HEADER, KEY_1).with(servletRequest -> {
                    ((org.springframework.mock.web.MockHttpServletRequest) servletRequest).setContent(new byte[400_000]);
                    return servletRequest;
                }))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("payload-too-large"));
    }

    @Test
    void readingAnIdStillWorksAndIsA404() throws Exception {
        mvc.perform(get(CHECKS + "/00000000-0000-0000-0000-000000000000").header(HEADER, KEY_1))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("image-check-not-found"));
    }
}
