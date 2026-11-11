package com.thanhdatpb.listingguard.api.image;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_2;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.CHECKS;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.ENABLED;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.additional;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.flat;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.main;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.metadata;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.notAnImage;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.product;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.request;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.requestWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.image.ImageApiSupport.Part;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * {@code POST /api/v1/image-checks} and {@code GET /api/v1/image-checks/{id}} (ADR-008, ADR-010) with the endpoint switched on
 * and the test-only rulesets of {@code src/test/resources/image} (invented thresholds; the shipped rulesets have none, so a
 * check against them is always INCOMPLETE): complete and incomplete results, an image that cannot be read, the declared source,
 * request validation, the cache and client isolation, and that no image is stored. Plans are off here (the default).
 */
@SpringBootTest(properties = {
        "listing-guard.image.enabled=true",
        "listing-guard.image.rulesets.AMAZON=classpath:image/amazon-image-test.yaml",
        "listing-guard.image.rulesets.ETSY=classpath:image/etsy-image-test.yaml",
        "listing-guard.image.max-images=6",
        "listing-guard.image.max-file-size=200KB"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ImageCheckApiIntegrationTest {

    private static final AtomicInteger NEXT = new AtomicInteger(400);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /** A product image no other test has sent, so a cache hit in one test is never another test's result. */
    private static byte[] fresh(String format) {
        return product(NEXT.incrementAndGet(), 300, format, 0);
    }

    private ResultActions perform(String key, MockMultipartHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HEADER, key));
    }

    private JsonNode ok(String key, MockMultipartHttpServletRequestBuilder request) throws Exception {
        return MAPPER.readTree(perform(key, request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode read(String key, String id) throws Exception {
        return MAPPER.readTree(mvc.perform(get(CHECKS + "/" + id).header(HEADER, key)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<JsonNode> withRule(JsonNode findings, String ruleId) {
        List<JsonNode> found = new ArrayList<>();
        findings.forEach(finding -> {
            if (finding.get("ruleId").asText().equals(ruleId)) {
                found.add(finding);
            }
        });
        return found;
    }

    // ---- results ---------------------------------------------------------------------------------------------------

    @Test
    void aCleanSetIsCompleteAndReadyAndTheSameRequestAgainIsTheStoredResult() throws Exception {
        List<Part> parts = List.of(main(fresh("PNG")), additional(fresh("JPEG")));

        JsonNode first = ok(KEY_1, request("AMAZON", parts));

        assertThat(first.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(first.get("summary").asText()).isEqualTo("READY");
        assertThat(first.get("cached").asBoolean()).isFalse();
        assertThat(first.get("marketplace").asText()).isEqualTo("AMAZON");
        assertThat(first.get("profile").asText()).isEqualTo("default");
        assertThat(first.get("rulesetVersion").asText()).isEqualTo("amazon-image-test-1");
        assertThat(first.get("failedRules")).isEmpty();
        assertThat(first.get("findings")).isEmpty();
        assertThat(first.get("disclaimer").asText()).startsWith("Test disclaimer");
        assertThat(first.get("images")).hasSize(2);
        JsonNode image = first.get("images").get(0);
        assertThat(image.get("ordinal").asInt()).isZero();
        assertThat(image.get("role").asText()).isEqualTo("MAIN");
        assertThat(image.get("source").asText()).isEqualTo("SELLER");
        assertThat(image.get("imageHash").asText()).hasSize(64);
        assertThat(image.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(image.get("metadata").get("format").asText()).isEqualTo("PNG");
        assertThat(image.get("metadata").get("height").asInt()).isEqualTo(300);
        assertThat(first.get("images").get(1).get("source").asText()).as("an omitted source is UNKNOWN").isEqualTo("UNKNOWN");
        assertThat(first.get("images").get(1).get("metadata").get("format").asText()).isEqualTo("JPEG");

        JsonNode again = ok(KEY_1, request("AMAZON", parts));
        assertThat(again.get("cached").asBoolean()).isTrue();
        assertThat(again.get("checkId")).isEqualTo(first.get("checkId"));

        JsonNode stored = read(KEY_1, first.get("checkId").asText());
        assertThat(stored.get("cached").asBoolean()).isFalse();
        assertThat(stored.get("checkId")).isEqualTo(first.get("checkId"));
        assertThat(stored.get("images")).isEqualTo(first.get("images"));
        assertThat(stored.get("analysisVersion")).isEqualTo(first.get("analysisVersion"));
    }

    @Test
    void findingsAreReportedWithTheirSeverityAndTheSourceStatusAndTheSummaryFollowsTheRuleset() throws Exception {
        // 50 x 50 grey: under the minimum size (width and height), no white border, nothing sharp
        List<Part> parts = List.of(main(flat(50, 50, "PNG")));

        JsonNode result = ok(KEY_1, request("AMAZON", parts));

        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(result.get("summary").asText()).isEqualTo("REVIEW");
        JsonNode findings = result.get("images").get(0).get("findings");
        assertThat(withRule(findings, "image_dimensions")).hasSize(2);
        assertThat(withRule(findings, "main_background")).hasSize(1);
        assertThat(withRule(findings, "blur")).hasSize(1);
        JsonNode dimensions = withRule(findings, "image_dimensions").get(0);
        assertThat(dimensions.get("severity").asText()).isEqualTo("WARNING");
        assertThat(dimensions.get("sourceStatus").asText()).isEqualTo("unverified");
        assertThat(dimensions.get("message").asText()).startsWith("Test - ");
        assertThat(dimensions.get("evidence").asText()).startsWith("value=");
        assertThat(withRule(findings, "blur").get(0).get("severity").asText()).isEqualTo("INFO");
        assertThat(read(KEY_1, result.get("checkId").asText()).get("images").get(0).get("findings")).isEqualTo(findings);
    }

    @Test
    void theNumberOfImagesIsAFindingOfThePackNotOfAnImage() throws Exception {
        // the test ruleset allows 5 images and this deployment 6 per request
        List<Part> parts = new ArrayList<>();
        parts.add(main(fresh("PNG")));
        for (int i = 0; i < 5; i++) {
            parts.add(additional(fresh("PNG")));
        }

        JsonNode result = ok(KEY_1, request("AMAZON", parts));

        assertThat(withRule(result.get("findings"), "image_count")).hasSize(1);
        assertThat(result.get("findings").get(0).get("evidence").asText()).isEqualTo("count=6");
        assertThat(result.get("summary").asText()).isEqualTo("REVIEW");
        assertThat(read(KEY_1, result.get("checkId").asText()).get("findings")).isEqualTo(result.get("findings"));
    }

    @Test
    void anImageThatCannotBeReadIsAnIncompleteImageNotAnErrorAndTheOthersAreStillChecked() throws Exception {
        List<Part> parts = List.of(main(fresh("PNG")), additional(notAnImage()));

        JsonNode result = ok(KEY_1, request("AMAZON", parts));

        assertThat(result.get("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.get("summary").asText()).as("never a clean summary").isEqualTo("INCOMPLETE");
        assertThat(result.get("failedRules").toString()).contains("images[1].image_decode");
        assertThat(result.get("images").get(0).get("completeness").asText()).isEqualTo("COMPLETE");
        JsonNode broken = result.get("images").get(1);
        assertThat(broken.get("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(broken.get("failedRules").toString()).contains("image_decode");
        assertThat(broken.has("metadata")).isFalse();
        assertThat(read(KEY_1, result.get("checkId").asText()).get("images").get(1).has("metadata")).isFalse();

        JsonNode again = ok(KEY_1, request("AMAZON", parts));
        assertThat(again.get("cached").asBoolean()).as("an INCOMPLETE result is never reused").isFalse();
        assertThat(again.get("checkId")).isNotEqualTo(result.get("checkId"));
        // but it is stored, so that its id can be read
        assertThat(read(KEY_1, again.get("checkId").asText()).get("completeness").asText()).isEqualTo("INCOMPLETE");
    }

    @Test
    void etsyHasNoBackgroundRuleSoItsMainImageIsCheckedLikeAnyOtherImage() throws Exception {
        // the white-background rule is Amazon's: the Etsy test ruleset has no background thresholds and the check is still complete
        JsonNode result = ok(KEY_1, request("ETSY", List.of(main(fresh("PNG")))));

        assertThat(result.get("rulesetVersion").asText()).isEqualTo("etsy-image-test-1");
        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
    }

    // ---- the declared source -----------------------------------------------------------------------------------------

    @Test
    void aCustomerContentImageGetsExactlyOneWarningEvenWhenItCannotBeDecodedAndOtherSourcesGetNone() throws Exception {
        List<Part> parts = List.of(main(fresh("PNG")), additional(fresh("PNG")).withSource("CUSTOMER_CONTENT"),
                additional(notAnImage()).withSource("CUSTOMER_CONTENT"), additional(fresh("PNG")).withSource("UNKNOWN"),
                additional(fresh("PNG")).withSource("SELLER"));

        JsonNode result = ok(KEY_1, request("AMAZON", parts));

        JsonNode images = result.get("images");
        assertThat(withRule(images.get(0).get("findings"), "customer_content")).isEmpty();
        assertThat(withRule(images.get(1).get("findings"), "customer_content")).hasSize(1);
        assertThat(withRule(images.get(2).get("findings"), "customer_content")).as("also when the image cannot be decoded").hasSize(1);
        assertThat(images.get(2).get("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(withRule(images.get(3).get("findings"), "customer_content")).isEmpty();
        assertThat(withRule(images.get(4).get("findings"), "customer_content")).isEmpty();
        JsonNode finding = withRule(images.get(1).get("findings"), "customer_content").get(0);
        assertThat(finding.get("severity").asText()).isEqualTo("WARNING");
        assertThat(finding.get("message").asText()).isEqualTo("Test - a customer-supplied image may contain a third-party mark; check it by hand.");
        assertThat(finding.get("sourceStatus").asText()).isEqualTo("unverified");
    }

    @Test
    void theSourceTheRoleAndTheProfileAreEachPartOfTheCacheIdentity() throws Exception {
        byte[] bytes = fresh("PNG");
        JsonNode base = ok(KEY_1, request("AMAZON", List.of(main(bytes))));

        assertThat(ok(KEY_1, request("AMAZON", List.of(main(bytes)))).get("cached").asBoolean()).isTrue();
        JsonNode otherSource = ok(KEY_1, request("AMAZON", List.of(main(bytes).withSource("CUSTOMER_CONTENT"))));
        assertThat(otherSource.get("cached").asBoolean()).isFalse();
        assertThat(otherSource.get("checkId")).isNotEqualTo(base.get("checkId"));
        JsonNode otherRole = ok(KEY_1, request("AMAZON", List.of(main(bytes).withRole("ADDITIONAL"))));
        assertThat(otherRole.get("cached").asBoolean()).isFalse();
        List<Part> parts = List.of(main(bytes));
        JsonNode otherProfile = ok(KEY_1, requestWith(metadata("AMAZON", "wide", parts), parts));
        assertThat(otherProfile.get("cached").asBoolean()).isFalse();
        assertThat(otherProfile.get("profile").asText()).isEqualTo("wide");
        // an omitted source is the same as UNKNOWN
        byte[] other = fresh("PNG");
        JsonNode omitted = ok(KEY_1, request("AMAZON", List.of(main(other).withSource(null))));
        assertThat(ok(KEY_1, request("AMAZON", List.of(main(other).withSource("UNKNOWN")))).get("checkId"))
                .isEqualTo(omitted.get("checkId"));
    }

    // ---- who sees what -----------------------------------------------------------------------------------------------

    @Test
    void anotherClientNeverSeesAResultAndNeverHitsItsCache() throws Exception {
        List<Part> parts = List.of(main(fresh("PNG")));
        JsonNode mine = ok(KEY_1, request("AMAZON", parts));

        mvc.perform(get(CHECKS + "/" + mine.get("checkId").asText()).header(HEADER, KEY_2)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("image-check-not-found"));
        JsonNode theirs = ok(KEY_2, request("AMAZON", parts));
        assertThat(theirs.get("cached").asBoolean()).isFalse();
        assertThat(theirs.get("checkId")).isNotEqualTo(mine.get("checkId"));
    }

    @Test
    void anUnknownIdIsA404AndNoKeyIsA401() throws Exception {
        mvc.perform(get(CHECKS + "/00000000-0000-0000-0000-000000000000").header(HEADER, KEY_1))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("image-check-not-found"));
        mvc.perform(get(CHECKS + "/not-a-uuid").header(HEADER, KEY_1)).andExpect(status().isBadRequest());
        mvc.perform(request("AMAZON", List.of(main(fresh("PNG"))))).andExpect(status().isUnauthorized());
    }

    // ---- the request -------------------------------------------------------------------------------------------------

    @Test
    void aBadRequestIsA400WithAStableCodeAndNeverA500() throws Exception {
        byte[] png = fresh("PNG");
        List<Part> one = List.of(main(png));

        perform(KEY_1, request("AMAZON", List.of(main(png).withSource("COMPANY"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("images[0].source")));
        perform(KEY_1, request("AMAZON", List.of(main(png).withRole(null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("role")));
        perform(KEY_1, request("AMAZON", List.of(main(png).withRole("HERO"))))
                .andExpect(status().isBadRequest());
        perform(KEY_1, requestWith(metadata("AMAZON", null, List.of(main(png), main(png))), one))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"));
        perform(KEY_1, requestWith("{\"marketplace\":\"AMAZON\",\"images\":[{\"role\":\"MAIN\",\"extra\":1}]}", one))
                .andExpect(status().isBadRequest());
        perform(KEY_1, requestWith("not json at all", one)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("not json"))));
        perform(KEY_1, requestWith("{\"images\":[{\"role\":\"MAIN\"}]}", one)).andExpect(status().isBadRequest());
        perform(KEY_1, request("NOWHERE", one)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown-marketplace"));
        perform(KEY_1, requestWith(metadata("AMAZON", "square", one), one)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown-image-profile"))
                .andExpect(jsonPath("$.known[0]").value("default"));
        // no image part at all, and no metadata part at all
        perform(KEY_1, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(CHECKS).file(
                new MockMultipartFile("metadata", "", "application/json", metadata("AMAZON", null, one).getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest());
        perform(KEY_1, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(CHECKS).file(
                new MockMultipartFile("images", "a.png", "image/png", png))).andExpect(status().isBadRequest());
    }

    @Test
    void moreImagesThanTheDeploymentAllowsIsA400WithTheLimit() throws Exception {
        List<Part> parts = new ArrayList<>();
        parts.add(main(fresh("PNG")));
        for (int i = 0; i < 6; i++) {
            parts.add(additional(fresh("PNG")));
        }

        perform(KEY_1, request("AMAZON", parts)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("too-many-images")).andExpect(jsonPath("$.maxImages").value(6))
                .andExpect(jsonPath("$.submitted").value(7));
    }

    @Test
    void anImageOverTheFileLimitIsA413() throws Exception {
        byte[] big = new byte[300 * 1024];

        perform(KEY_1, request("AMAZON", List.of(main(big)))).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("payload-too-large"));
    }

    @Test
    void aBodyThatIsNotMultipartIsA415() throws Exception {
        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("unsupported-media-type"));
    }

    // ---- what is stored ----------------------------------------------------------------------------------------------

    @Test
    void noImageAndNoFileNameIsStoredOnlyHashesMetadataAndFindings() throws Exception {
        byte[] bytes = fresh("PNG");
        JsonNode result = ok(KEY_1, request("AMAZON", List.of(main(bytes))));

        Integer binaryColumns = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name LIKE 'image_check%' AND data_type IN ('bytea', 'oid')", Integer.class);
        assertThat(binaryColumns).isZero();
        Integer fileNames = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name LIKE 'image_check%' AND column_name ILIKE '%name%'", Integer.class);
        assertThat(fileNames).isZero();
        assertThat(result.toString()).doesNotContain("photo-0");
        String hash = jdbc.queryForObject("SELECT i.image_hash FROM image_check_item i WHERE i.check_id = ?::uuid",
                String.class, result.get("checkId").asText());
        assertThat(hash).isEqualTo(result.get("images").get(0).get("imageHash").asText());
        Integer expires = jdbc.queryForObject("SELECT count(*) FROM image_check WHERE id = ?::uuid AND expires_at > now() "
                + "AND expires_at < now() + interval '31 days'", Integer.class, result.get("checkId").asText());
        assertThat(expires).as("the 30-day retention").isEqualTo(1);
    }
}
