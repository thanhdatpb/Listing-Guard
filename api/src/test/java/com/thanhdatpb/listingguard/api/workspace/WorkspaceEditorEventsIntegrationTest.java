package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the editor says, and when (ADR-062 §2): {@link WorkspaceEvents.ListingSaved} is heard only after the commit, with what was written
 * already stored, and never when the transaction is rolled back; a clone says {@link WorkspaceEvents.ListingsImported}, as an import does,
 * and nothing is said for a save that changed nothing. A listener of this test stands in for the background audit, so it works with the
 * audit off.
 */
@SpringBootTest(properties = {
        "listing-guard.workspace.enabled=true",
        "spring.servlet.multipart.max-file-size=25MB",
        "spring.servlet.multipart.max-request-size=60MB",
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, WorkspaceEditorEventsIntegrationTest.Heard.class})
class WorkspaceEditorEventsIntegrationTest {

    /** What a listener heard, with the version the listing had in the database at that moment. */
    record Seen(String event, UUID listing, long versionInDatabase) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Heard {

        static final List<Seen> SEEN = new ArrayList<>();

        @Bean
        Listener listener(JdbcTemplate jdbc) {
            return new Listener(jdbc);
        }

        static final class Listener {
            private final JdbcTemplate jdbc;

            Listener(JdbcTemplate jdbc) {
                this.jdbc = jdbc;
            }

            @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
            void saved(WorkspaceEvents.ListingSaved event) {
                SEEN.add(new Seen("saved", event.listingId(), version(event.listingId())));
            }

            @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
            void imported(WorkspaceEvents.ListingsImported event) {
                event.listingIds().forEach(id -> SEEN.add(new Seen("imported", id, version(id))));
            }

            private long version(UUID id) {
                return jdbc.queryForObject("SELECT version FROM workspace_listing WHERE id = ?", Long.class, id);
            }
        }
    }

    @Autowired
    ClientProvisioning provisioning;
    @Autowired
    ConsoleAccountRepository console;
    @Autowired
    WorkspaceAccess access;
    @Autowired
    WorkspaceAccounts accounts;
    @Autowired
    WorkspaceListings listings;
    @Autowired
    ListingEditor editor;
    @Autowired
    ListingCloner cloner;
    @Autowired
    TransactionTemplate transaction;

    @MockitoBean
    JavaMailSender mailSender;

    @BeforeEach
    void forgetWhatWasHeard() {
        Heard.SEEN.clear();
    }

    private Actor member(Client client) {
        ConsoleUser user = new ConsoleUser("member-" + UUID.randomUUID() + "@example.test", client.getId());
        console.saveAndFlush(new ConsoleAccount(user.email(), client.getId(), ConsoleRole.MEMBER));
        return access.actor(user).orElseThrow();
    }

    private Client client() {
        return provisioning.createClient("wv" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
    }

    @Test
    void aSaveIsHeardAfterTheCommitWithTheNewVersionAlreadyStored() {
        Client client = client();
        Actor actor = member(client);
        SellerAccount shop = accounts.create(client.getId(), Marketplace.ETSY, "Shop", null, ExportFormat.XLSM);
        Listing created = editor.create(actor, shop.id(), Map.of("sku", "S-1", "title", "A mug"));
        assertThat(Heard.SEEN).containsExactly(new Seen("saved", created.id(), 0));
        Heard.SEEN.clear();

        Map<String, String> posted = new LinkedHashMap<>(created.data());
        posted.put("title", "A better mug");
        editor.save(actor, created.id(), created.version(), posted);

        assertThat(Heard.SEEN).containsExactly(new Seen("saved", created.id(), 1));
    }

    @Test
    void aSaveInATransactionThatRollsBackIsNeverHeardAndWritesNothing() {
        Client client = client();
        Actor actor = member(client);
        SellerAccount shop = accounts.create(client.getId(), Marketplace.ETSY, "Shop", null, ExportFormat.XLSM);
        Listing created = editor.create(actor, shop.id(), Map.of("sku", "S-1", "title", "A mug"));
        Heard.SEEN.clear();
        Map<String, String> posted = new LinkedHashMap<>(created.data());
        posted.put("title", "Rolled back");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            editor.save(actor, created.id(), created.version(), posted);
            throw new IllegalStateException("the surrounding work failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(Heard.SEEN).isEmpty();
        assertThat(listings.find(client.getId(), created.id()).orElseThrow().title()).isEqualTo("A mug");
    }

    @Test
    void aSaveThatChangesNothingSaysNothing() {
        Client client = client();
        Actor actor = member(client);
        SellerAccount shop = accounts.create(client.getId(), Marketplace.ETSY, "Shop", null, ExportFormat.XLSM);
        Listing created = editor.create(actor, shop.id(), Map.of("sku", "S-1", "title", "A mug"));
        Heard.SEEN.clear();

        editor.save(actor, created.id(), created.version(), new LinkedHashMap<>(created.data()));

        assertThat(Heard.SEEN).isEmpty();
    }

    @Test
    void aCloneIsHeardAsAnImportOfTheNewListingsAfterTheCommit() {
        Client client = client();
        Actor actor = member(client);
        SellerAccount shop = accounts.create(client.getId(), Marketplace.ETSY, "Shop", null, ExportFormat.XLSM);
        Listing created = editor.create(actor, shop.id(), Map.of("sku", "S-1", "title", "A mug"));
        Heard.SEEN.clear();

        List<Listing> clones = cloner.clone(actor, new ListingCloner.Request(shop.id(), List.of(created.id()), false,
                new SkuPlan(SkuPlan.Mode.PATTERN, "", "-C", "", "", null, 0, Map.of()), Map.of(), false));

        assertThat(Heard.SEEN).containsExactly(new Seen("imported", clones.get(0).id(), 0));
    }
}
