package com.thanhdatpb.listingguard.api.trademark.sync;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.cancelled;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.live;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.write;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.xml;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.AdminCommands;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.trademark.CatalogActivations;
import com.thanhdatpb.listingguard.api.trademark.CatalogAdminCommands;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogImporter;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogLock;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.UsptoConvertProperties;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncJob.Options;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncJob.Result;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.RunStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An API that follows the activation log (ADR-009, PR A2d): after a sync activates a catalog, {@code GET
 * /api/v1/capabilities} says {@code READY} and not DEMO with no change to that endpoint, a trademark check answers from the
 * synced catalog (with the {@code uspto:} source ids and their links), a newer sync is served after the refresh, and the admin
 * job's rollback is served as well. Every mark and file is synthetic; the refresh is called by the test instead of waiting
 * for its schedule.
 */
@SpringBootTest(properties = {"listing-guard.trademark.activation.follow=true",
        "listing-guard.trademark.activation.refresh-interval=1h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class UsptoSyncCapabilitiesIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    TrademarkCatalogRuntime runtime;

    @Autowired
    UsptoConvertProperties convert;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    ResourceLoader resources;

    @Autowired
    UsptoSyncStore store;

    @Autowired
    TrademarkCatalogImporter importer;

    @Autowired
    CatalogActivations activations;

    @Autowired
    TrademarkCatalogLock lock;

    @Autowired
    CatalogAdminCommands catalogCommands;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @TempDir
    Path catalogs;

    @BeforeEach
    void nothingActivated() {
        jdbc.execute("TRUNCATE uspto_sync_file, uspto_sync_run, trademark_catalog_activation");
        runtime.evaluate();
    }

    private UsptoSyncJob job() {
        UsptoSyncProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "listing-guard.trademark.uspto.sync.min-free-disk", "1MB",
                "listing-guard.trademark.uspto.sync.catalog.license", "Synthetic test files; no third-party terms")))
                .bindOrCreate("listing-guard.trademark.uspto.sync", UsptoSyncProperties.class);
        return new UsptoSyncJob(properties, convert, new TrademarkProperties(
                "classpath:rulesets/trademark-risk-2026-11b.yaml", List.of("ETSY", "AMAZON"), "", catalogs.toString()),
                rulesets, resources, store, importer, activations, lock, jdbc, transactions, null, Clock.systemUTC());
    }

    private Path inbox(String folder) {
        return catalogs.resolve("raw").resolve("inbox").resolve(folder);
    }

    private JsonNode capabilities() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/capabilities").header(HEADER, KEY_1)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static boolean trademarkAvailable(JsonNode capabilities) {
        for (JsonNode pack : capabilities.get("packs")) {
            if (pack.get("id").asText().equals("trademark-risk")) {
                return pack.get("available").asBoolean();
            }
        }
        throw new AssertionError("no trademark-risk pack in " + capabilities);
    }

    private MvcResult check(String title) throws Exception {
        String body = """
                {"marketplace": "ETSY", "listing": {"title": "%s",
                 "description": "A plain description of a handmade item for the sync test.", "tags": ["handmade"]}}"""
                .formatted(title);
        return mvc.perform(post("/api/v1/trademark-checks").header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    private JsonNode finding(JsonNode result, String mark) {
        for (JsonNode finding : result.get("findings")) {
            if (finding.hasNonNull("mark") && finding.get("mark").asText().equalsIgnoreCase(mark)) {
                return finding;
            }
        }
        return null;
    }

    private String admin(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new AdminCommands(provisioning, catalogCommands, new PrintStream(out, true, StandardCharsets.UTF_8))
                .execute(new DefaultApplicationArguments(args));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aSyncedCatalogIsReadyInTheCapabilitiesServedByChecksAndRolledBack() throws Exception {
        JsonNode before = capabilities();
        assertThat(before.at("/trademarkCatalog/state").asText()).isEqualTo("DISABLED");
        assertThat(before.at("/trademarkCatalog/demo").asBoolean()).isFalse();
        assertThat(trademarkAvailable(before)).isFalse();
        assertThat(check("Handmade Zorvex hoodie").getResponse().getStatus()).as("no catalog yet").isEqualTo(503);

        write(inbox("backfile"), "apc18840407-20251231-01.zip", zip("apc18840407-20251231-01.zip", xml(
                live("90000001", "20251201", "Zorvex"), live("90000002", "20251202", "Brasktone"))));
        Result first = job().run(new Options("inbox/backfile", true, null, false, false, false, List.of(), null, null));
        assertThat(first.status()).isEqualTo(RunStatus.ACTIVATED);

        assertThat(runtime.refreshActivation()).isTrue();
        String raw = mvc.perform(get("/api/v1/capabilities").header(HEADER, KEY_1)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode after = mapper.readTree(raw);
        assertThat(after.at("/trademarkCatalog/state").asText()).isEqualTo("READY");
        assertThat(after.at("/trademarkCatalog/demo").asBoolean()).isFalse();
        assertThat(trademarkAvailable(after)).isTrue();
        assertThat(raw).as("the catalog's name is never shown").doesNotContain(first.catalogVersion());

        MvcResult served = check("Handmade Zorvex hoodie");
        assertThat(served.getResponse().getStatus()).isEqualTo(200);
        JsonNode result = mapper.readTree(served.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(result.get("catalogVersion").asText()).isEqualTo(first.catalogVersion());
        JsonNode zorvex = finding(result, "Zorvex");
        assertThat(zorvex).as("a finding for the synced mark in %s", result.get("findings")).isNotNull();
        assertThat(zorvex.get("sourceIds").get(0).asText()).isEqualTo("uspto:90000001");
        assertThat(zorvex.get("sourceLinks").get(0).get("url").asText()).contains("90000001");

        write(inbox("day1"), "apc260105.zip", zip("apc260105.zip", xml(
                cancelled("90000001", "20260105", "Zorvex"), live("90000005", "20260105", "Flintwave"))));
        Result second = job().run(new Options("inbox/day1", false, null, false, false, false, List.of(), null, null));
        assertThat(second.status()).isEqualTo(RunStatus.ACTIVATED);
        assertThat(runtime.refreshActivation()).isTrue();
        JsonNode newer = mapper.readTree(check("Handmade Zorvex hoodie").getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(newer.get("catalogVersion").asText()).isEqualTo(second.catalogVersion());
        assertThat(finding(newer, "Zorvex")).as("cancelled in the later daily file").isNull();

        assertThat(admin("catalog", "rollback")).contains("Rolled back from " + second.catalogVersion() + " to "
                + first.catalogVersion());
        assertThat(runtime.refreshActivation()).isTrue();
        JsonNode rolledBack = mapper.readTree(check("Handmade Zorvex hoodie").getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(rolledBack.get("catalogVersion").asText()).isEqualTo(first.catalogVersion());
        assertThat(finding(rolledBack, "Zorvex")).isNotNull();
        assertThat(capabilities().at("/trademarkCatalog/state").asText()).isEqualTo("READY");
    }
}
