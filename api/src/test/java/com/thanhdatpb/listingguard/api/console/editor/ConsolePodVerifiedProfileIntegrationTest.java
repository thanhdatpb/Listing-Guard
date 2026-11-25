package com.thanhdatpb.listingguard.api.console.editor;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * POD readiness against verified profiles (ADR-048). The two shipped v1 profiles are unverified, so their readiness is always
 * incomplete; these synthetic profiles (api/src/test/resources/pod-synthetic, invented numbers and codes) show the production
 * result moving from INCOMPLETE to NO_FINDINGS or REVIEW_REQUIRED once a print area is verified.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "listing-guard.plans.enabled=true",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class,
        ConsolePodVerifiedProfileIntegrationTest.SyntheticProfiles.class})
class ConsolePodVerifiedProfileIntegrationTest {
    private static final String POD = "/console/pod-readiness";

    @TestConfiguration
    static class SyntheticProfiles {
        @Bean
        @Primary
        PodProfiles syntheticPodProfiles(ObjectMapper mapper, ResourcePatternResolver resources) throws IOException {
            return new PodProfiles(mapper, resources, "classpath:/pod-synthetic/*.json");
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private ClientProvisioning provisioning;
    @Autowired private ClientRepository clients;
    @MockitoBean private JavaMailSender mailSender;

    @Test
    void theProfilePickerGroupsVersionsByProductTypeNewestFirst() throws Exception {
        Document page = html(mvc.perform(get(POD).with(signedIn(user())).param("lang", "en")));

        var groups = page.select("select#profileKey optgroup");
        assertThat(groups).extracting(group -> group.attr("label")).containsExactly("sample-ornament", "sample-tee");
        assertThat(groups.get(0).select("option")).extracting(option -> option.val()).containsExactly(
                "sample-ornament-v2", "sample-ornament-v1");
        assertThat(groups.get(0).select("option").get(1).text()).isEqualTo("sample-ornament · v1 · Verified");
        assertThat(groups.get(1).select("option").get(0).text()).isEqualTo("sample-tee · v1 · Unverified");
    }

    @Test
    void aVerifiedSizeThatTheArtworkFitsLeavesNothingIncompleteInProduction() throws Exception {
        Document report = html(tee("TEE-1 | S | Blue\nTEE-2 | M | Black", "250", "300"));

        assertThat(production(report)).isEqualTo("NO_FINDINGS");
        assertThat(report.select("#pod-print-areas tbody tr")).hasSize(3);
        assertThat(report.selectFirst("#pod-print-areas").text()).contains("BLK-TS-01", "BLK-TS-02", "Operator A",
                "2026-11-20", "280 × 350.5", "Verified", "Unverified");
    }

    @Test
    void anArtworkOverAVerifiedPrintAreaIsAFindingThatNamesTheSize() throws Exception {
        Document report = html(tee("TEE-1 | S | Blue\nTEE-2 | M | Black", "290", "300"));

        assertThat(production(report)).isEqualTo("REVIEW_REQUIRED");
        assertThat(report.text()).contains("Over the print area for S: at most 280 × 350.5 mm.")
                .doesNotContain("Over the print area for M");
    }

    @Test
    void aSizeWithAnUnverifiedAreaOrNoAreaAtAllKeepsProductionIncomplete() throws Exception {
        Document unverified = html(tee("TEE-1 | L | Blue", "100", "100"));
        assertThat(production(unverified)).isEqualTo("INCOMPLETE");
        assertThat(unverified.text()).contains("Print limits are unverified");

        Document unknown = html(tee("TEE-1 | XXL | Blue", "100", "100"));
        assertThat(production(unknown)).isEqualTo("INCOMPLETE");
        assertThat(unknown.text()).contains("Enter a size or variant that this profile has a print area for.");

        Document oneOfTwo = html(tee("TEE-1 | S | Blue\nTEE-2 | L | Black", "100", "100"));
        assertThat(production(oneOfTwo)).isEqualTo("INCOMPLETE");
    }

    @Test
    void aVerifiedLimitSetsTheSampleScreeningAndNamesTheProfileInItsAnalysisVersion() throws Exception {
        Document fits = html(ornament("sample-ornament-v1", true, "80", "80"));

        assertThat(production(fits)).isEqualTo("NO_FINDINGS");
        assertThat(fits.text()).contains("Fixed sample screened", "latin_basic+sample-ornament-v1")
                .doesNotContain("Confirm the allowed characters");
        assertThat(fits.select("p.lg-code").eachText()).anyMatch(text -> text.matches("Analysis version [0-9a-f]{64}"));

        Document over = html(ornament("sample-ornament-v1", true, "100", "80"));
        assertThat(production(over)).isEqualTo("REVIEW_REQUIRED");

        String v1 = analysisVersion(fits);
        String v2 = analysisVersion(html(ornament("sample-ornament-v2", true, "80", "80")));
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void anOrnamentProfileWithoutAVerifiedCharacterLimitOrConfirmationStaysIncomplete() throws Exception {
        Document noLimit = html(ornament("sample-ornament-v2", true, "80", "80"));
        assertThat(production(noLimit)).isEqualTo("INCOMPLETE");
        assertThat(noLimit.text()).contains("Confirm the allowed characters and length limits with the print provider.");

        Document unconfirmed = html(ornament("sample-ornament-v1", false, "80", "80"));
        assertThat(production(unconfirmed)).isEqualTo("INCOMPLETE");
        assertThat(unconfirmed.text()).contains("Configure the text personalization field");
    }

    private ResultActions tee(String variants, String width, String height) throws Exception {
        return mvc.perform(draft("sample-tee-v1").param("variants", variants)
                .param("artworkWidth", width).param("artworkHeight", height));
    }

    private ResultActions ornament(String profile, boolean configured, String width, String height) throws Exception {
        return mvc.perform(draft(profile).param("variants", "ORN-1").param("artworkWidth", width)
                .param("artworkHeight", height).param("personalizationConfigured", String.valueOf(configured)));
    }

    private MockHttpServletRequestBuilder draft(String profile) {
        return post(POD).with(signedIn(user())).with(csrf()).param("lang", "en").param("marketplace", "ETSY")
                .param("profileKey", profile).param("title", "Synthetic listing");
    }

    private static String production(Document report) {
        return report.selectFirst("#pod-production-status").attr("data-status");
    }

    private static String analysisVersion(Document report) {
        return report.select("p.lg-code").eachText().stream().filter(text -> text.startsWith("Analysis version "))
                .findFirst().orElseThrow();
    }

    private ConsoleUser user() {
        return userOf(newClient(provisioning, clients, PlanCode.SELLER));
    }

    private static Document html(ResultActions result) throws Exception {
        return Jsoup.parse(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
