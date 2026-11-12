package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.trademark.CatalogActivations.Activation;
import com.thanhdatpb.listingguard.api.trademark.CatalogActivations.Reason;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime.State;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogHeader;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The activation log (ADR-009, PR A2d, V31): what it accepts, how a rollback picks its version, that it is append-only, and
 * that a runtime following it serves the latest activation and swaps on a refresh. Every catalog is synthetic.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class CatalogActivationIntegrationTest {

    @Autowired
    CatalogActivations activations;

    @Autowired
    TrademarkCatalogImporter importer;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    TrademarkCatalogLock lock;

    @Autowired
    TrademarkCatalogRuntime defaultRuntime;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @TempDir
    Path dir;

    @BeforeEach
    void emptyLog() {
        // the log refuses DELETE; a test starts from an empty one
        jdbc.execute("TRUNCATE trademark_catalog_activation");
    }

    private CatalogHeader importCatalog(String label, TrademarkRuleset ruleset) {
        try {
            Path catalogDir = Files.createDirectories(dir.resolve(label));
            TrademarkFixtures.syntheticDir(catalogDir, label);
            return importer.importCatalog(CatalogManifest.load(catalogDir).entry(label), catalogDir.resolve("raw"),
                    new TextNormalizer(ruleset.normalization()), NormalizationIdentity.of(ruleset)).catalog();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private CatalogHeader importCatalog() {
        return importCatalog("act-" + UUID.randomUUID().toString().substring(0, 8), rulesets.ruleset());
    }

    private TrademarkCatalogRuntime following() {
        return new TrademarkCatalogRuntime(new TrademarkProperties("classpath:rulesets/trademark-risk-2026-11b.yaml",
                List.of("ETSY", "AMAZON"), "", "catalogs"), new TrademarkActivationProperties(true, Duration.ofMinutes(1)),
                jdbc, transactions, rulesets);
    }

    @Test
    void aFollowingRuntimeServesTheLatestActivationAndSwapsOnARefresh() {
        CatalogHeader first = importCatalog();
        CatalogHeader second = importCatalog();
        TrademarkCatalogRuntime runtime = following();

        assertThat(runtime.evaluate().state()).isEqualTo(State.DISABLED);
        assertThat(runtime.status().detail()).contains("no catalog version has been activated");
        assertThat(runtime.refreshActivation()).as("nothing changed").isFalse();

        Activation activation = activations.activate(first.version(), Reason.MANUAL, null, "first");
        assertThat(runtime.refreshActivation()).isTrue();
        assertThat(runtime.status()).isEqualTo(new TrademarkCatalogRuntime.Status(State.READY, first.version()));
        assertThat(runtime.serving("ETSY").catalogVersion()).isEqualTo(first.version());
        assertThat(runtime.refreshActivation()).as("the same activation is not evaluated twice").isFalse();
        assertThat(activation.reason()).isEqualTo(Reason.MANUAL);
        assertThat(activation.note()).isEqualTo("first");

        activations.activate(second.version(), Reason.SYNC, null, null);
        assertThat(runtime.status().detail()).as("served until the next refresh").isEqualTo(first.version());
        assertThat(runtime.refreshActivation()).isTrue();
        assertThat(runtime.serving("ETSY").catalogVersion()).isEqualTo(second.version());
        assertThat(runtime.scanner().scan(TrademarkCheckInput.ofTitle("Zorvex hoodie")).catalogVersion())
                .isEqualTo(second.version());
    }

    @Test
    void aRuntimeThatServesTheConfiguredVersionIgnoresTheLog() {
        activations.activate(importCatalog().version(), Reason.MANUAL, null, null);

        assertThat(defaultRuntime.followsActivation()).isFalse();
        assertThat(defaultRuntime.refreshActivation()).isFalse();
        assertThat(defaultRuntime.status().state()).isEqualTo(State.DISABLED);
    }

    @Test
    void followingTheLogAndAConfiguredVersionAtOnceStopsTheStartUp() {
        assertThatThrownBy(() -> new TrademarkCatalogRuntime(new TrademarkProperties(
                "classpath:rulesets/trademark-risk-2026-11b.yaml", List.of("ETSY"), "some-version-0123456789ab", "catalogs"),
                new TrademarkActivationProperties(true, Duration.ofMinutes(1)), jdbc, transactions, rulesets))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("choose one");
        assertThatThrownBy(() -> new TrademarkActivationProperties(true, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRollbackServesTheNewestEarlierVersionThatWasServed() {
        CatalogHeader a = importCatalog();
        CatalogHeader b = importCatalog();
        CatalogHeader neverServed = importCatalog();
        CatalogHeader c = importCatalog();
        activations.activate(a.version(), Reason.SYNC, null, null);
        activations.activate(b.version(), Reason.SYNC, null, null);
        activations.activate(c.version(), Reason.SYNC, null, null);

        Activation first = activations.rollback(null, "bad import");
        Activation second = activations.rollback(null, null);

        assertThat(first.catalogVersion()).as("not %s: it was never served", neverServed.version()).isEqualTo(b.version());
        assertThat(first.reason()).isEqualTo(Reason.ROLLBACK);
        assertThat(second.catalogVersion()).as("a second rollback goes further back").isEqualTo(a.version());
        assertThatThrownBy(() -> activations.rollback(null, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--to=");
        assertThat(activations.rollback(c.version(), null).catalogVersion()).isEqualTo(c.version());
        assertThatThrownBy(() -> activations.rollback(c.version(), null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already the served version");
        assertThat(activations.history(10)).extracting(Activation::reason).containsExactly(Reason.ROLLBACK, Reason.ROLLBACK,
                Reason.ROLLBACK, Reason.SYNC, Reason.SYNC, Reason.SYNC);
    }

    @Test
    void nothingToRollBackWhenNothingWasActivated() {
        importCatalog();

        assertThatThrownBy(() -> activations.rollback(null, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing to roll back");
    }

    @Test
    void aDemoCatalogIsNeverServedThroughTheLog() {
        CatalogHeader demo = importCatalog("demo-act-" + UUID.randomUUID().toString().substring(0, 8), rulesets.ruleset());

        assertThatThrownBy(() -> activations.activate(demo.version(), Reason.MANUAL, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DEMO");
        assertThat(activations.current()).isEmpty();

        // a row written around the service is refused by the runtime as well
        jdbc.update("INSERT INTO trademark_catalog_activation (catalog_version_id, catalog_version, reason) "
                + "SELECT id, version, 'MANUAL' FROM trademark_catalog_version WHERE version = ?", demo.version());
        TrademarkCatalogRuntime runtime = following();
        assertThat(runtime.evaluate().state()).isEqualTo(State.REFUSED);
        assertThat(runtime.status().detail()).contains("DEMO");
    }

    @Test
    void aVersionNormalizedOtherwiseCannotBeActivated() throws Exception {
        String yaml = new ClassPathResource("rulesets/trademark-risk-2026-11b.yaml").getContentAsString(StandardCharsets.UTF_8);
        TrademarkRuleset otherMapping = TrademarkRulesetLoader.parse(yaml.replace("\"0\": \"o\"", "\"0\": \"q\""));
        CatalogHeader other = importCatalog("act-other-" + UUID.randomUUID().toString().substring(0, 8), otherMapping);

        assertThatThrownBy(() -> activations.activate(other.version(), Reason.MANUAL, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("import it again");
        assertThatThrownBy(() -> activations.activate("no-such-version", Reason.MANUAL, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
    }

    @Test
    void theLogIsAppendOnlyButKeepsTheNameOfADeletedVersion() {
        CatalogHeader old = importCatalog();
        CatalogHeader current = importCatalog();
        Activation oldActivation = activations.activate(old.version(), Reason.SYNC, null, null);
        activations.activate(current.version(), Reason.SYNC, null, null);

        assertThatThrownBy(() -> jdbc.update("UPDATE trademark_catalog_activation SET reason = 'MANUAL' WHERE id = ?",
                oldActivation.id())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM trademark_catalog_activation WHERE id = ?", oldActivation.id()))
                .hasMessageContaining("append-only");

        deleteVersion(old.version());
        Activation kept = activations.history(10).get(1);
        assertThat(kept.catalogVersionId()).isNull();
        assertThat(kept.catalogVersion()).isEqualTo(old.version());

        // the served version deleted by hand: the following runtime refuses, it never serves nothing as if it were clean
        deleteVersion(current.version());
        TrademarkCatalogRuntime runtime = following();
        assertThat(runtime.evaluate().state()).isEqualTo(State.REFUSED);
        assertThat(runtime.status().detail()).contains("was deleted");
    }

    @Test
    void theCatalogLockHasOneHolderAtATime() {
        Optional<TrademarkCatalogLock.Held> held = lock.tryAcquire();
        assertThat(held).isPresent();
        try {
            assertThat(lock.tryAcquire()).as("another session cannot take it").isEmpty();
        } finally {
            held.get().close();
        }
        Optional<TrademarkCatalogLock.Held> again = lock.tryAcquire();
        assertThat(again).isPresent();
        again.get().close();
    }

    private void deleteVersion(String version) {
        Long id = jdbc.queryForObject("SELECT id FROM trademark_catalog_version WHERE version = ?", Long.class, version);
        jdbc.update("DELETE FROM trademark_entry WHERE catalog_version_id = ?", id);
        jdbc.update("DELETE FROM trademark_catalog_version WHERE id = ?", id);
    }
}
