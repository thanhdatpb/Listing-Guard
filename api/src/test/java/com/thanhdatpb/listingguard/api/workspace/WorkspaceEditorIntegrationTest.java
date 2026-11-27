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
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Plan;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Result;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.SourceFile;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingSource;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import com.thanhdatpb.listingguard.workspace.sheets.SheetReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The editor, the new listing, the clone, the history, the comparison with the original and the conflicts of an import, against a real
 * database (ADR-062), with the background audit off. Every account, SKU and title is invented, and no file was read from a real Amazon
 * template, so what a real template looks like is not verified here.
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
@Import(TestcontainersConfig.class)
class WorkspaceEditorIntegrationTest {

    private record Team(Client client, Actor admin, Actor member, Actor other) {
    }

    /** The header of a second Amazon template: other order, other columns, three bullets, a column the first has no partner for. */
    private static final List<String> SECOND = List.of("item_name", "item_sku", "feed_product_type", "bullet_point1", "bullet_point2",
            "bullet_point3", "generic_keywords", "parentage", "parent_sku", "size_name", "color_name", "price_note", "product_description");

    @Autowired
    ClientProvisioning provisioning;
    @Autowired
    ConsoleAccountRepository console;
    @Autowired
    WorkspaceAccess access;
    @Autowired
    WorkspaceAccounts accounts;
    @Autowired
    WorkspaceTemplates templates;
    @Autowired
    WorkspaceListings listings;
    @Autowired
    ListingImportService imports;
    @Autowired
    ListingEditor editor;
    @Autowired
    ListingCloner cloner;
    @Autowired
    ListingHistory history;
    @Autowired
    ListingComparison comparison;
    @Autowired
    ListingStatusService statusService;
    @Autowired
    ListingContent content;
    @Autowired
    WorkspaceProperties properties;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    com.thanhdatpb.listingguard.api.client.ClientRepository clients;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate transaction;
    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    @MockitoBean
    JavaMailSender mailSender;

    private Team team() {
        Client client = provisioning.createClient("we" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        String id = UUID.randomUUID().toString();
        ConsoleUser admin = new ConsoleUser("admin-" + id + "@example.test", client.getId());
        ConsoleUser member = new ConsoleUser("member-" + id + "@example.test", client.getId());
        ConsoleUser other = new ConsoleUser("other-" + id + "@example.test", client.getId());
        console.saveAndFlush(new ConsoleAccount(admin.email(), client.getId(), ConsoleRole.ADMIN));
        console.saveAndFlush(new ConsoleAccount(member.email(), client.getId(), ConsoleRole.MEMBER));
        console.saveAndFlush(new ConsoleAccount(other.email(), client.getId(), ConsoleRole.MEMBER));
        return new Team(client, access.actor(admin).orElseThrow(), access.actor(member).orElseThrow(), access.actor(other).orElseThrow());
    }

    private SellerAccount account(Team team, Marketplace marketplace, String name) {
        return accounts.create(team.client().getId(), marketplace, name, null, ExportFormat.XLSM);
    }

    private Result run(Actor actor, SellerAccount account, TemplateKind kind, byte[] bytes, String sheet, int fieldRow, UUID owner) {
        SheetGrid grid = SheetReader.read(bytes, sheet, properties.limits(false));
        Params params = params(actor, account, kind, grid, sheet, fieldRow, owner, Set.of(), Set.of());
        return imports.commit(actor, params, grid, new SourceFile(SheetReader.detect(bytes, properties.limits(false)), "template-file", bytes), null);
    }

    private Params params(Actor actor, SellerAccount account, TemplateKind kind, SheetGrid grid, String sheet, int fieldRow, UUID owner,
                          Set<Integer> overwriteLines, Set<Integer> overwriteGroups) {
        var view = imports.mappingView(actor, account.id(), kind, grid, fieldRow);
        Map<Integer, String> targets = new LinkedHashMap<>();
        view.columns().forEach(c -> {
            if (!c.target().isEmpty()) {
                targets.put(c.index(), c.target());
            }
        });
        return new Params(account.id(), kind, owner, ListingStatus.DRAFT, sheet, fieldRow, fieldRow + 1, targets, Map.of(), overwriteLines, overwriteGroups);
    }

    /** An Amazon account with the synthetic family E-P1 (parent), E-P1-S, E-P1-M and the single E-S2, owned by the member. */
    private SellerAccount amazonWithFamily(Team team, String name, String prefix) throws IOException {
        SellerAccount account = account(team, Marketplace.AMAZON, name);
        run(team.admin(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile(prefix), "Template", 3, team.member().accountId());
        return account;
    }

    private Listing listing(Team team, SellerAccount account, String sku) {
        UUID id = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND account_id = ? AND sku = ? AND deleted_at IS NULL",
                UUID.class, team.client().getId(), account.id(), sku);
        return listings.find(team.client().getId(), id).orElseThrow();
    }

    private long historyCount(Listing listing) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workspace_listing_history WHERE listing_id = ?", Long.class, listing.id());
    }

    private static Map<String, String> asPosted(Map<String, String> data) {
        return new LinkedHashMap<>(data);
    }

    // ------------------------------------------------------------------ 1. the editor writes from the columns only

    @Test
    void savingWithoutChangingAnythingChangesNothingAndWritesNoHistory() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing before = listing(team, account, "E-P1-S");
        long entries = historyCount(before);
        Map<String, String> posted = asPosted(before.data());
        // a browser sends a line break as CR LF: it is the same text
        posted.replaceAll((key, value) -> value.replace("\n", "\r\n"));

        ListingEditor.Saved saved = editor.save(team.admin(), before.id(), before.version(), posted);

        Listing after = listing(team, account, "E-P1-S");
        assertThat(saved.changed()).isFalse();
        assertThat(after.data()).isEqualTo(before.data());
        assertThat(after.fields()).isEqualTo(before.fields());
        assertThat(after.contentHash()).isEqualTo(before.contentHash());
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(historyCount(after)).isEqualTo(entries);
    }

    @Test
    void anEditOfABulletAndTheSearchTermsBuildsTheFieldsTheTitleAndTheHashAgainAndKeepsAHistoryEntry() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing before = listing(team, account, "E-P1-S");
        assertThat(before.fields().path("search_terms").asText()).isEqualTo("gift");
        Map<String, String> posted = asPosted(before.data());
        posted.put("bullet_point2", "Warmer than before");
        posted.put("generic_keywords", "gift present");
        posted.put("item_name", "Synthetic shirt S, new title");

        ListingEditor.Saved saved = editor.save(team.admin(), before.id(), before.version(), posted);

        Listing after = listing(team, account, "E-P1-S");
        assertThat(saved.changed()).isTrue();
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.fields().get("bullet_points")).extracting(n -> n.asText()).containsExactly("Soft", "Warmer than before");
        assertThat(after.fields().path("search_terms").asText()).isEqualTo("gift present");
        assertThat(after.title()).isEqualTo("Synthetic shirt S, new title");
        // the same builder as the import's, from the stored columns, gives the stored result
        var template = templates.find(team.client().getId(), after.templateId()).orElseThrow();
        assertThat(content.build(Marketplace.AMAZON, template, after.data()).fields()).isEqualTo(after.fields());
        assertThat(after.contentHash()).isEqualTo(ListingContent.hash(after.data(), after.role()));
        assertThat(after.contentHash()).isNotEqualTo(before.contentHash());
        // what was not touched is as it was, and the SKU, the parent and the owner are the same
        assertThat(after.data()).containsEntry("x_custom_flag", "yes").containsEntry("size_name", "S");
        assertThat(after.sku()).isEqualTo("E-P1-S");
        assertThat(after.parentSku()).isEqualTo("E-P1");
        assertThat(after.role()).isEqualTo(ListingRole.CHILD);
        assertThat(after.ownerAccountId()).isEqualTo(before.ownerAccountId());
        assertThat(listings.history(team.client().getId(), before.id())).extracting(h -> h.kind().name()).first().isEqualTo("EDIT");
        // the previous version is kept whole: the history can show what it said
        assertThat(listings.historyEntry(team.client().getId(), before.id(), before.version()).orElseThrow().data()).containsEntry("generic_keywords", "gift");
    }

    @Test
    void theSkuColumnCannotBeChangedByTheEditor() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing before = listing(team, account, "E-S2");
        Map<String, String> posted = asPosted(before.data());
        posted.put("item_sku", "E-HIJACK");
        posted.put("item_name", "Changed so that the save is not a no-op");

        editor.save(team.admin(), before.id(), before.version(), posted);

        assertThat(listing(team, account, "E-S2").data()).containsEntry("item_sku", "E-S2");
        assertThat(listings.exists(team.client().getId(), account.id(), "E-HIJACK")).isFalse();
    }

    @Test
    void aParentThatDoesNotExistIsRefusedAndNothingIsWritten() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing before = listing(team, account, "E-P1-S");
        Map<String, String> posted = asPosted(before.data());
        posted.put("parent_sku", "E-NOWHERE");

        assertThatThrownBy(() -> editor.save(team.admin(), before.id(), before.version(), posted)).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("parent.missing"));
        assertThat(listing(team, account, "E-P1-S").version()).isEqualTo(before.version());
    }

    @Test
    void theSecondOfTwoSavesFromTheSameVersionIsRefusedWithWhatIsStoredNow() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing opened = listing(team, account, "E-S2");
        Map<String, String> first = asPosted(opened.data());
        first.put("item_name", "First person's title");
        Map<String, String> second = asPosted(opened.data());
        second.put("product_description", "Second person's description");

        editor.save(team.admin(), opened.id(), opened.version(), first);

        assertThatThrownBy(() -> editor.save(team.admin(), opened.id(), opened.version(), second)).isInstanceOfSatisfying(
                ListingEditor.VersionConflict.class, conflict -> {
                    assertThat(conflict.current().version()).isEqualTo(opened.version() + 1);
                    assertThat(conflict.current().data()).containsEntry("item_name", "First person's title");
                });
        assertThat(listing(team, account, "E-S2").data()).doesNotContainEntry("product_description", "Second person's description");
        // the base the second person started from is still kept, so a page can say which columns the other person changed
        assertThat(history.snapshot(team.client().getId(), opened.id(), opened.version())).isPresent();
    }

    @Test
    void aMemberChangesOnlyTheirOwnListingsButCloneAnyIntoTheirOwnDrafts() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing theirs = listing(team, account, "E-S2");
        jdbc.update("UPDATE workspace_listing SET owner_account_id = ? WHERE id = ?", team.other().accountId(), theirs.id());
        Listing before = listing(team, account, "E-S2");
        Map<String, String> posted = asPosted(before.data());
        posted.put("item_name", "Not allowed");

        assertThatThrownBy(() -> editor.save(team.member(), before.id(), before.version(), posted)).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("forbidden"));
        assertThatThrownBy(() -> history.restore(team.member(), before.id(), before.version(), before.version())).isInstanceOf(WorkspaceException.class);
        assertThat(listing(team, account, "E-S2").data()).isEqualTo(before.data());

        List<Listing> clone = cloner.clone(team.member(), new ListingCloner.Request(account.id(), List.of(before.id()), false,
                new SkuPlan(SkuPlan.Mode.PATTERN, "", "-MINE", "", "", null, 0, Map.of()), Map.of(), false));

        assertThat(clone).singleElement().satisfies(l -> {
            assertThat(l.sku()).isEqualTo("E-S2-MINE");
            assertThat(l.ownerAccountId()).isEqualTo(team.member().accountId());
            assertThat(l.status()).isEqualTo(ListingStatus.DRAFT);
            assertThat(l.source()).isEqualTo(ListingSource.CLONE);
            assertThat(l.clonedFrom()).isEqualTo(before.id());
            assertThat(l.version()).isZero();
        });
        // and the member can now edit their clone
        Listing mine = listing(team, account, "E-S2-MINE");
        Map<String, String> mineData = asPosted(mine.data());
        mineData.put("item_name", "Mine now");
        assertThat(editor.save(team.member(), mine.id(), mine.version(), mineData).changed()).isTrue();
    }

    // ------------------------------------------------------------------ 2. a new listing

    @Test
    void aNewEtsyListingIsMadeFromTheFixedFieldsAndTheCreatorIsResponsible() {
        Team team = team();
        SellerAccount shop = account(team, Marketplace.ETSY, "Etsy Shop");
        Map<String, String> posted = new LinkedHashMap<>();
        posted.put("sku", "ET-1");
        posted.put("title", "A new mug");
        posted.put("description", "Ceramic mug");
        posted.put("tags", "mug, ceramic, gift");
        posted.put("price", "19.99");

        Listing created = editor.create(team.member(), shop.id(), posted);

        assertThat(created.source()).isEqualTo(ListingSource.NEW);
        assertThat(created.status()).isEqualTo(ListingStatus.DRAFT);
        assertThat(created.ownerAccountId()).isEqualTo(team.member().accountId());
        assertThat(created.fields().get("tags")).extracting(n -> n.asText()).containsExactly("mug", "ceramic", "gift");
        assertThat(created.title()).isEqualTo("A new mug");
        assertThat(listings.history(team.client().getId(), created.id())).extracting(h -> h.kind().name()).containsExactly("NEW");
        // the SKU is required and unique in the account
        assertThatThrownBy(() -> editor.create(team.member(), shop.id(), posted)).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("sku.duplicate"));
        assertThatThrownBy(() -> editor.create(team.member(), shop.id(), Map.of("title", "No SKU"))).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("sku.required"));
        // another account of the client may use the same SKU
        SellerAccount second = account(team, Marketplace.ETSY, "Etsy Shop Two");
        assertThat(editor.create(team.member(), second.id(), posted).sku()).isEqualTo("ET-1");
        // an Etsy listing that was saved keeps its tags as many columns do when edited
        Map<String, String> edit = asPosted(created.data());
        edit.put("tags", "mug, ceramic");
        editor.save(team.member(), created.id(), created.version(), edit);
        assertThat(listings.find(team.client().getId(), created.id()).orElseThrow().fields().get("tags")).extracting(n -> n.asText()).containsExactly("mug", "ceramic");
    }

    @Test
    void aNewAmazonListingNeedsATemplateAndIsWrittenInTheNewestOne() throws IOException {
        Team team = team();
        SellerAccount empty = account(team, Marketplace.AMAZON, "No Template Yet");
        assertThatThrownBy(() -> editor.create(team.member(), empty.id(), Map.of("item_sku", "X"))).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("template.none"));
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");

        Listing created = editor.create(team.member(), account.id(), Map.of("item_sku", "E-NEW", "item_name", "Brand new", "bullet_point1", "One"));

        assertThat(created.templateId()).isEqualTo(templates.current(team.client().getId(), account.id(), TemplateKind.AMAZON_BULK).orElseThrow().id());
        assertThat(created.fields().path("title").asText()).isEqualTo("Brand new");
        assertThat(created.data()).containsEntry("item_sku", "E-NEW");
    }

    // ------------------------------------------------------------------ 3. clones

    private ListingCloner.Request request(SellerAccount target, List<UUID> ids, boolean family, SkuPlan plan) {
        return new ListingCloner.Request(target.id(), ids, family, plan, Map.of(), false);
    }

    private static SkuPlan suffix(String suffix) {
        return new SkuPlan(SkuPlan.Mode.PATTERN, "", suffix, "", "", null, 0, Map.of());
    }

    @Test
    void aFamilyIsClonedWithItsRelationAndTheChildrenPointAtTheNewParent() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing parent = listing(team, account, "E-P1");

        List<Listing> cloned = cloner.clone(team.admin(), request(account, List.of(parent.id()), true, suffix("-C")));

        assertThat(cloned).extracting(Listing::sku).containsExactlyInAnyOrder("E-P1-C", "E-P1-S-C", "E-P1-M-C");
        Listing newParent = listing(team, account, "E-P1-C");
        Listing newChild = listing(team, account, "E-P1-S-C");
        assertThat(newParent.role()).isEqualTo(ListingRole.PARENT);
        assertThat(newChild.role()).isEqualTo(ListingRole.CHILD);
        assertThat(newChild.parentSku()).isEqualTo("E-P1-C");
        assertThat(newChild.data()).containsEntry("parent_sku", "E-P1-C").containsEntry("item_sku", "E-P1-S-C");
        assertThat(newChild.fields().get("bullet_points")).extracting(n -> n.asText()).containsExactly("Soft", "Warm");
        assertThat(newChild.clonedFrom()).isEqualTo(listing(team, account, "E-P1-S").id());
        assertThat(newChild.ownerAccountId()).isEqualTo(team.admin().accountId());
        assertThat(newChild.status()).isEqualTo(ListingStatus.DRAFT);
        assertThat(newChild.source()).isEqualTo(ListingSource.CLONE);
        assertThat(listings.history(team.client().getId(), newChild.id())).extracting(h -> h.kind().name()).containsExactly("CLONE");
        // the originals are untouched
        assertThat(listing(team, account, "E-P1-S").parentSku()).isEqualTo("E-P1");
        assertThat(listings.children(team.client().getId(), account.id(), "E-P1")).hasSize(2);
    }

    @Test
    void aSelectionIsCloned_aChildAloneKeepsItsParentInTheSameAccountAndLosesItInAnother() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        SellerAccount other = amazonWithFamily(team, "Shop Two", "F-");
        Listing child = listing(team, account, "E-P1-S");
        Listing single = listing(team, account, "E-S2");

        cloner.clone(team.member(), request(account, List.of(child.id(), single.id()), false, suffix("-X")));
        assertThat(listing(team, account, "E-P1-S-X").parentSku()).as("same account: a sibling of the original").isEqualTo("E-P1");
        assertThat(listing(team, account, "E-S2-X").role()).isEqualTo(ListingRole.STANDALONE);

        ListingCloner.Preview preview = cloner.preview(team.member(), request(other, List.of(child.id()), false, suffix("-X")));
        assertThat(preview.notices()).extracting(ListingCloner.Notice::code).containsExactly("parent_dropped");
        cloner.clone(team.member(), request(other, List.of(child.id()), false, suffix("-X")));
        Listing moved = listing(team, other, "E-P1-S-X");
        assertThat(moved.parentSku()).isNull();
        assertThat(moved.role()).isEqualTo(ListingRole.STANDALONE);
        assertThat(moved.data()).doesNotContainKey("parent_sku").doesNotContainEntry("parentage", "child");
    }

    @Test
    void theSkusAreCheckedInTheAccountAndInTheBatchBeforeAnythingIsWritten() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing parent = listing(team, account, "E-P1");
        long before = listings.count(team.client().getId(), account.id());

        // unchanged SKUs are taken by the originals
        ListingCloner.Preview same = cloner.preview(team.member(), request(account, List.of(parent.id()), true, SkuPlan.same()));
        assertThat(same.items()).allSatisfy(i -> assertThat(i.problems()).containsExactly("sku.taken"));
        assertThat(same.canWrite()).isFalse();
        assertThatThrownBy(() -> cloner.clone(team.member(), request(account, List.of(parent.id()), true, SkuPlan.same()))).isInstanceOfSatisfying(
                WorkspaceException.class, e -> assertThat(e.code()).isEqualTo("clone.invalid"));

        // two SKUs that become the same in the batch
        SkuPlan collapse = new SkuPlan(SkuPlan.Mode.PATTERN, "", "", "-S", "-M", null, 0, Map.of());
        ListingCloner.Preview repeated = cloner.preview(team.member(), request(account,
                List.of(listing(team, account, "E-P1-S").id(), listing(team, account, "E-P1-M").id()), false, collapse));
        assertThat(repeated.items()).extracting(ListingCloner.Item::problems).containsOnly(List.of("sku.taken", "sku.repeated"));
        ListingCloner.Preview twice = cloner.preview(team.member(), request(account,
                List.of(listing(team, account, "E-P1-S").id(), listing(team, account, "E-P1-M").id()), false,
                new SkuPlan(SkuPlan.Mode.MANUAL, "", "", "", "", null, 0, Map.of(listing(team, account, "E-P1-S").id(), "DUP", listing(team, account, "E-P1-M").id(), "DUP"))));
        assertThat(twice.items()).allSatisfy(i -> assertThat(i.problems()).containsExactly("sku.repeated"));

        // an empty typed SKU, and one too long
        ListingCloner.Preview empty = cloner.preview(team.member(), request(account, List.of(parent.id()), false,
                new SkuPlan(SkuPlan.Mode.MANUAL, "", "", "", "", null, 0, Map.of(parent.id(), "x".repeat(300)))));
        assertThat(empty.items().get(0).problems()).containsExactly("sku.long");
        assertThat(cloner.preview(team.member(), request(account, List.of(parent.id()), false,
                new SkuPlan(SkuPlan.Mode.MANUAL, "", "", "", "", null, 0, Map.of()))).items().get(0).problems()).containsExactly("sku.empty");
        assertThat(listings.count(team.client().getId(), account.id())).isEqualTo(before);
    }

    @Test
    void aPatternMakesNumberedSkusInTheOrderOfTheClone() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing parent = listing(team, account, "E-P1");

        ListingCloner.Preview preview = cloner.preview(team.member(), request(account, List.of(parent.id()), true,
                new SkuPlan(SkuPlan.Mode.PATTERN, "N-", "-", "E-", "", 1, 2, Map.of())));

        assertThat(preview.items()).extracting(ListingCloner.Item::newSku).containsExactly("N-P1-01", "N-P1-M-02", "N-P1-S-03");
        assertThat(preview.canWrite()).isTrue();
    }

    @Test
    void cloningToAnotherAccountMapsTheColumnsByStandardFieldAndListsWhatHasNoPartner() throws IOException {
        Team team = team();
        SellerAccount first = amazonWithFamily(team, "Shop One", "E-");
        SellerAccount second = account(team, Marketplace.AMAZON, "Shop Two");
        run(team.admin(), second, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile("T-", SECOND, List.of(
                List.of("Seed title", "T-SEED", "SHIRT", "One", "Two", "Three", "kw", "", "", "M", "Red", "n", "Desc"))), "Template", 3, null);
        Listing child = listing(team, first, "E-P1-S");
        ListingCloner.Request base = request(second, List.of(child.id()), false, SkuPlan.same());

        ListingCloner.Preview preview = cloner.preview(team.member(), base);

        assertThat(preview.sameAccount()).isFalse();
        // the n-th column of a field goes to the n-th of the other template, whatever its position or name
        assertThat(preview.pairs()).extracting(ListingCloner.Pair::sourceKey, ListingCloner.Pair::targetKey).contains(
                org.assertj.core.groups.Tuple.tuple("item_sku", "item_sku"), org.assertj.core.groups.Tuple.tuple("item_name", "item_name"),
                org.assertj.core.groups.Tuple.tuple("bullet_point1", "bullet_point1"), org.assertj.core.groups.Tuple.tuple("bullet_point2", "bullet_point2"),
                org.assertj.core.groups.Tuple.tuple("generic_keywords", "generic_keywords"), org.assertj.core.groups.Tuple.tuple("size_name", "size_name"),
                org.assertj.core.groups.Tuple.tuple("color_name", "color_name"), org.assertj.core.groups.Tuple.tuple("product_description", "product_description"));
        // what has a value and no partner is listed for the person to place or leave out
        assertThat(preview.unmapped()).extracting(ListingCloner.Unmapped::sourceKey).contains("x_custom_flag").doesNotContain("item_name");
        assertThat(preview.free()).extracting(ListingCloner.TargetColumn::key).contains("price_note", "bullet_point3");

        // left out: the value is not copied
        cloner.clone(team.member(), request(second, List.of(child.id()), false, suffix("-X")));
        Listing skipped = listing(team, second, "E-P1-S-X");
        assertThat(skipped.data()).doesNotContainKey("price_note").containsEntry("item_sku", "E-P1-S-X").containsEntry("size_name", "S");
        assertThat(skipped.fields().get("bullet_points")).extracting(n -> n.asText()).containsExactly("Soft", "Warm");
        assertThat(skipped.templateId()).isEqualTo(templates.current(team.client().getId(), second.id(), TemplateKind.AMAZON_BULK).orElseThrow().id());

        // placed in a column the person chose
        ListingCloner.Request placed = new ListingCloner.Request(second.id(), List.of(child.id()), false, suffix("-Y"), Map.of("x_custom_flag", "price_note"), false);
        cloner.clone(team.member(), placed);
        assertThat(listing(team, second, "E-P1-S-Y").data()).containsEntry("price_note", "yes");
        // a column of the other account cannot be given to two columns
        ListingCloner.Request twice = new ListingCloner.Request(second.id(), List.of(child.id()), false, suffix("-Z"),
                Map.of("x_custom_flag", "price_note", "standard_price", "price_note"), false);
        assertThat(cloner.preview(team.member(), twice).canWrite()).isFalse();
    }

    @Test
    void aCloneNeedsAnAccountOfTheSameMarketplaceAndOfTheSameClient() throws IOException {
        Team team = team();
        Team stranger = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        SellerAccount etsy = account(team, Marketplace.ETSY, "Etsy Shop");
        SellerAccount foreign = account(stranger, Marketplace.AMAZON, "Foreign");
        Listing single = listing(team, account, "E-S2");

        assertThatThrownBy(() -> cloner.preview(team.member(), request(etsy, List.of(single.id()), false, suffix("-X")))).isInstanceOfSatisfying(
                WorkspaceException.class, e -> assertThat(e.code()).isEqualTo("clone.marketplace"));
        assertThatThrownBy(() -> cloner.preview(team.member(), request(foreign, List.of(single.id()), false, suffix("-X")))).isInstanceOfSatisfying(
                WorkspaceException.class, e -> assertThat(e.code()).isEqualTo("account.unknown"));
        assertThatThrownBy(() -> cloner.preview(stranger.member(), request(foreign, List.of(single.id()), false, suffix("-X")))).isInstanceOfSatisfying(
                WorkspaceException.class, e -> assertThat(e.code()).isEqualTo("clone.listing.unknown"));
    }

    @Test
    void anEtsyListingIsClonedIntoAnotherShop() {
        Team team = team();
        SellerAccount one = account(team, Marketplace.ETSY, "Etsy One");
        SellerAccount two = account(team, Marketplace.ETSY, "Etsy Two");
        Listing source = editor.create(team.member(), one.id(), Map.of("sku", "ET-1", "title", "A mug", "tags", "mug, gift"));

        List<Listing> cloned = cloner.clone(team.member(), request(two, List.of(source.id()), false, SkuPlan.same()));

        assertThat(cloned).singleElement().satisfies(l -> {
            assertThat(l.accountId()).isEqualTo(two.id());
            assertThat(l.sku()).isEqualTo("ET-1");
            assertThat(l.fields().get("tags")).extracting(n -> n.asText()).containsExactly("mug", "gift");
            assertThat(l.clonedFrom()).isEqualTo(source.id());
        });
    }

    @Test
    void theCustomizeRowOfTheSourceSkuGoesToTheNewSkuWhenAsked() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        run(team.admin(), account, TemplateKind.AMAZON_CUSTOM,
                WorkspaceFixtures.customizeFile(List.of(List.of("E-P1-S", "Front", "Name"), List.of("E-S2", "Back", "Number"))), "Custom", 2, null);
        Listing child = listing(team, account, "E-P1-S");

        ListingCloner.Preview preview = cloner.preview(team.member(), new ListingCloner.Request(account.id(), List.of(child.id()), false,
                suffix("-C"), Map.of(), true));
        assertThat(preview.customizations()).isEqualTo(1);
        cloner.clone(team.member(), new ListingCloner.Request(account.id(), List.of(child.id()), false, suffix("-C"), Map.of(), true));
        cloner.clone(team.member(), new ListingCloner.Request(account.id(), List.of(listing(team, account, "E-S2").id()), false, suffix("-N"), Map.of(), false));

        assertThat(listings.customization(team.client().getId(), account.id(), "E-P1-S-C")).hasValueSatisfying(row -> {
            assertThat(row.data()).containsEntry("item_sku", "E-P1-S-C").containsEntry("surface_name", "Front").containsEntry("text_input_label", "Name");
            assertThat(row.listingId()).isEqualTo(listing(team, account, "E-P1-S-C").id());
        });
        assertThat(listings.customization(team.client().getId(), account.id(), "E-S2-N")).as("not asked for").isEmpty();
        assertThat(listings.customization(team.client().getId(), account.id(), "E-P1-S")).isPresent();
    }

    // ------------------------------------------------------------------ 4. compared with the original

    @Test
    void aCloneMarksTheColumnsThatAreStillTheOriginalsAndWarnsOfARepeatedTitle() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing original = listing(team, account, "E-S2");
        cloner.clone(team.member(), request(account, List.of(original.id()), false, suffix("-C")));
        Listing copy = listing(team, account, "E-S2-C");
        var template = templates.find(team.client().getId(), copy.templateId()).orElseThrow();
        var seller = accounts.find(team.client().getId(), account.id()).orElseThrow();

        ListingComparison.Comparison fresh = comparison.of(team.client().getId(), copy, template, seller).orElseThrow();

        assertThat(fresh.original().id()).isEqualTo(original.id());
        assertThat(fresh.untouched()).contains("item_name", "bullet_point1", "generic_keywords", "product_description").doesNotContain("item_sku");
        assertThat(fresh.sameAsOriginal()).isTrue();
        assertThat(fresh.titleWarning()).isTrue();

        Map<String, String> posted = asPosted(copy.data());
        posted.put("item_name", "A different mug");
        editor.save(team.member(), copy.id(), copy.version(), posted);
        Listing edited = listing(team, account, "E-S2-C");
        ListingComparison.Comparison after = comparison.of(team.client().getId(), edited, template, seller).orElseThrow();
        assertThat(after.untouched()).doesNotContain("item_name").contains("bullet_point1");
        assertThat(after.titleWarning()).isFalse();

        // another listing of the account with the same title is named, and the audit result is not touched by any of this
        Listing third = listing(team, account, "E-P1-S");
        Map<String, String> clash = asPosted(edited.data());
        clash.put("item_name", third.title());
        editor.save(team.member(), edited.id(), edited.version(), clash);
        ListingComparison.Comparison named = comparison.of(team.client().getId(), listing(team, account, "E-S2-C"), template, seller).orElseThrow();
        assertThat(named.sameAsOriginal()).isFalse();
        assertThat(named.sameAccount()).extracting(WorkspaceListings.TitleMatch::sku).containsExactly("E-P1-S");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'workspace_listing_audit'", Long.class)).isNotNull();
    }

    @Test
    void aListingThatWasNotClonedOrWhoseOriginalIsGoneHasNoComparison() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing plain = listing(team, account, "E-S2");
        var template = templates.find(team.client().getId(), plain.templateId()).orElseThrow();
        var seller = accounts.find(team.client().getId(), account.id()).orElseThrow();
        assertThat(comparison.of(team.client().getId(), plain, template, seller)).isEmpty();

        cloner.clone(team.member(), request(account, List.of(plain.id()), false, suffix("-C")));
        listings.softDelete(team.client().getId(), plain.id(), plain.version(), team.admin().accountId());

        assertThat(comparison.of(team.client().getId(), listing(team, account, "E-S2-C"), template, seller)).isEmpty();
    }

    // ------------------------------------------------------------------ 5. history and restore

    @Test
    void anEarlierVersionIsRestoredAsANewVersionAndTheCurrentOneStaysInTheHistory() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing v0 = listing(team, account, "E-S2");
        Map<String, String> one = asPosted(v0.data());
        one.put("item_name", "Second title");
        editor.save(team.admin(), v0.id(), v0.version(), one);
        Listing v1 = listing(team, account, "E-S2");
        Map<String, String> two = asPosted(v1.data());
        two.put("item_name", "Third title");
        two.put("bullet_point1", "Changed bullet");
        editor.save(team.admin(), v1.id(), v1.version(), two);
        Listing v2 = listing(team, account, "E-S2");

        var diff = ListingHistory.diff(templates.find(team.client().getId(), v2.templateId()).orElseThrow(),
                history.snapshot(team.client().getId(), v2.id(), v0.version()).orElseThrow().data(),
                history.snapshot(team.client().getId(), v2.id(), v2.version()).orElseThrow().data());
        assertThat(diff).extracting(ListingHistory.ColumnChange::key).containsExactly("item_name", "bullet_point1");
        assertThat(diff.get(0).before()).isEqualTo("Synthetic mug");
        assertThat(diff.get(0).after()).isEqualTo("Third title");

        ListingHistory.Restored restored = history.restore(team.admin(), v2.id(), v0.version(), v2.version());

        Listing back = listing(team, account, "E-S2");
        assertThat(restored.dropped()).isZero();
        assertThat(back.version()).isEqualTo(v2.version() + 1);
        assertThat(back.data()).isEqualTo(v0.data());
        assertThat(back.title()).isEqualTo("Synthetic mug");
        assertThat(back.sku()).isEqualTo("E-S2");
        assertThat(back.fields()).isEqualTo(v0.fields());
        assertThat(listings.history(team.client().getId(), v2.id())).extracting(h -> h.kind().name()).first().isEqualTo("RESTORE");
        assertThat(history.snapshot(team.client().getId(), v2.id(), v2.version()).orElseThrow().data()).containsEntry("item_name", "Third title");
        // restoring what is already there, or from a stale page, changes nothing
        assertThatThrownBy(() -> history.restore(team.admin(), v2.id(), back.version(), back.version())).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("restore.same"));
        assertThatThrownBy(() -> history.restore(team.admin(), v2.id(), v0.version(), v2.version())).isInstanceOf(ListingEditor.VersionConflict.class);
    }

    // ------------------------------------------------------------------ 6. status (the check is off here)

    @Test
    void withTheAuditOffNothingHoldsADraftBackAndAReadyListingGoesBackToDraftWhenItsContentChanges() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing mine = listing(team, account, "E-S2");
        Listing notMine = listing(team, account, "E-P1");
        jdbc.update("UPDATE workspace_listing SET owner_account_id = ? WHERE id = ?", team.other().accountId(), notMine.id());
        assertThat(statusService.gateActive()).isFalse();

        var outcomes = statusService.markReady(team.member(), List.of(mine.id(), notMine.id(), UUID.randomUUID()), false, null);

        assertThat(outcomes).extracting(ListingStatusService.Outcome::result).containsExactlyInAnyOrder(ListingStatusService.Result.MOVED,
                ListingStatusService.Result.FORBIDDEN, ListingStatusService.Result.UNKNOWN);
        Listing ready = listing(team, account, "E-S2");
        assertThat(ready.status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
        assertThat(ready.version()).isEqualTo(mine.version() + 1);
        assertThat(listings.history(team.client().getId(), ready.id())).extracting(h -> h.kind().name()).first().isEqualTo("STATUS");
        assertThat(statusService.markReady(team.member(), List.of(mine.id()), false, null)).extracting(ListingStatusService.Outcome::result)
                .containsExactly(ListingStatusService.Result.NOT_DRAFT);

        Map<String, String> posted = asPosted(ready.data());
        posted.put("item_name", "Changed after it was ready");
        ListingEditor.Saved saved = editor.save(team.member(), ready.id(), ready.version(), posted);
        assertThat(saved.readyReverted()).isTrue();
        assertThat(listing(team, account, "E-S2").status()).isEqualTo(ListingStatus.DRAFT);
        // only an administrator opens a block, and only with a reason
        assertThatThrownBy(() -> statusService.markReady(team.member(), List.of(mine.id()), true, "because")).isInstanceOf(WorkspaceException.class);
        assertThatThrownBy(() -> statusService.markReady(team.admin(), List.of(mine.id()), true, "  ")).isInstanceOf(WorkspaceException.class);
    }

    // ------------------------------------------------------------------ 7. importing again over a listing that was changed in the workspace

    @Test
    void anImportOfAListingChangedInTheWorkspaceSkipsItUnlessThePersonOverwritesIt() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing edited = listing(team, account, "E-S2");
        Map<String, String> posted = asPosted(edited.data());
        posted.put("item_name", "Changed in the workspace");
        editor.save(team.admin(), edited.id(), edited.version(), posted);
        // the file is the one of the first import with one more change in another listing
        List<List<String>> rows = new java.util.ArrayList<>(WorkspaceFixtures.dataRows("E-"));
        List<String> child = new java.util.ArrayList<>(rows.get(1));
        child.set(2, "Synthetic shirt S, from the file");
        rows.set(1, child);
        byte[] file = WorkspaceFixtures.flatFile("E-", WorkspaceFixtures.FIELDS, rows);
        SheetGrid grid = SheetReader.read(file, "Template", properties.limits(false));

        Plan plan = imports.plan(team.admin(), params(team.admin(), account, TemplateKind.AMAZON_BULK, grid, "Template", 3, null, Set.of(), Set.of()), grid);

        assertThat(plan.counts().updated()).as("only the other listing is a plain update").isEqualTo(1);
        assertThat(plan.edited()).singleElement().satisfies(row -> {
            assertThat(row.sku()).isEqualTo("E-S2");
            assertThat(row.kind().name()).isEqualTo("EDIT");
            assertThat(row.overwrite()).isFalse();
        });
        assertThat(plan.canWrite()).isTrue();

        Result skipped = imports.commit(team.admin(), params(team.admin(), account, TemplateKind.AMAZON_BULK, grid, "Template", 3, null, Set.of(), Set.of()),
                grid, new SourceFile(SheetReader.detect(file, properties.limits(false)), "f", file), null);
        assertThat(skipped.counts().updated()).isEqualTo(1);
        assertThat(skipped.keptEdited()).isEqualTo(1);
        assertThat(skipped.overwritten()).isZero();
        assertThat(listing(team, account, "E-S2").data()).containsEntry("item_name", "Changed in the workspace");
        assertThat(listing(team, account, "E-P1-S").data()).containsEntry("item_name", "Synthetic shirt S, from the file");

        int line = plan.edited().get(0).line();
        Plan chosen = imports.plan(team.admin(), params(team.admin(), account, TemplateKind.AMAZON_BULK, grid, "Template", 3, null, Set.of(line), Set.of()), grid);
        assertThat(chosen.edited().get(0).overwrite()).isTrue();
        Result written = imports.commit(team.admin(), params(team.admin(), account, TemplateKind.AMAZON_BULK, grid, "Template", 3, null, Set.of(line), Set.of()),
                grid, new SourceFile(SheetReader.detect(file, properties.limits(false)), "f", file), null);
        assertThat(written.overwritten()).isEqualTo(1);
        assertThat(written.counts().updated()).isEqualTo(1);
        assertThat(listing(team, account, "E-S2").data()).containsEntry("item_name", "Synthetic mug");
        // once written by an import, the listing is an imported one again: the next file is an ordinary update
        assertThat(imports.plan(team.admin(), params(team.admin(), account, TemplateKind.AMAZON_BULK, grid, "Template", 3, null, Set.of(), Set.of()), grid)
                .edited()).isEmpty();
    }

    @Test
    void aFamilyIsOverwrittenAsAGroupAndRowsNobodyChangedKeepTheOldBehaviour() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        for (String sku : List.of("E-P1", "E-P1-S")) {
            Listing l = listing(team, account, sku);
            Map<String, String> posted = asPosted(l.data());
            posted.put("item_name", "Edited " + sku);
            editor.save(team.admin(), l.id(), l.version(), posted);
        }
        // a cloned listing has no import at all: a file that names its SKU finds it changed in the workspace
        cloner.clone(team.admin(), request(account, List.of(listing(team, account, "E-S2").id()), false, suffix("-C")));
        List<List<String>> rows = new java.util.ArrayList<>(WorkspaceFixtures.dataRows("E-"));
        List<String> clone = new java.util.ArrayList<>(rows.get(3));
        clone.set(0, "E-S2-C");
        clone.set(2, "From the file");
        rows.add(clone);
        byte[] file = WorkspaceFixtures.flatFile("E-", WorkspaceFixtures.FIELDS, rows);
        SheetGrid grid = SheetReader.read(file, "Template", properties.limits(false));
        Plan plan = imports.plan(team.admin(), params(team.admin(), account, TemplateKind.AMAZON_BULK, grid, "Template", 3, null, Set.of(), Set.of()), grid);

        assertThat(plan.edited()).extracting(e -> e.sku()).containsExactlyInAnyOrder("E-P1", "E-P1-S", "E-S2-C");
        assertThat(plan.edited()).filteredOn(e -> e.sku().equals("E-S2-C")).singleElement().extracting(e -> e.kind().name()).isEqualTo("CLONE");
        int parentLine = plan.edited().stream().filter(e -> e.sku().equals("E-P1")).findFirst().orElseThrow().line();

        Plan group = imports.plan(team.admin(), params(team.admin(), account, TemplateKind.AMAZON_BULK, grid, "Template", 3, null, Set.of(), Set.of(parentLine)), grid);

        assertThat(group.edited()).filteredOn(e -> e.overwrite()).extracting(e -> e.sku()).containsExactlyInAnyOrder("E-P1", "E-P1-S");
        // the row that was never in the file before and the unchanged rows are what W1 made of them
        assertThat(group.counts().unchanged()).isEqualTo(2);
        assertThat(group.counts().errors()).isZero();
    }

    // ------------------------------------------------------------------ 8. purge and export carry the new rows

    @Test
    void theNewTableBelongsToTheExportAndThePurgeAndTheTenantsDoNotMeet(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws IOException {
        Team team = team();
        Team stranger = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing listing = listing(team, account, "E-S2");
        assertThat(WorkspaceDataCommands.TABLES).contains("workspace_status_override");
        jdbc.update("INSERT INTO workspace_status_override (client_id, listing_id, opened_by, reason, content_hash, audit_state, block_reasons) "
                + "VALUES (?, ?, ?, 'because', ?, 'NONE', 'UNCHECKED')", team.client().getId(), listing.id(), team.admin().accountId(), listing.contentHash());

        // the export says why a blocked listing was let go, and the purge removes the row with the rest
        WorkspaceDataCommands commands = new WorkspaceDataCommands(jdbc, clients, mapper, transaction, new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        java.nio.file.Path directory = commands.export(team.client(), temp.resolve("export").toString(), false);
        String tsv;
        try (var files = java.nio.file.Files.list(directory)) {
            tsv = java.nio.file.Files.readString(files.filter(f -> f.getFileName().toString().endsWith(".tsv")).findFirst().orElseThrow());
        }
        assertThat(tsv).contains("status_overrides").contains("because").contains("UNCHECKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_status_override WHERE client_id = ?", Long.class, team.client().getId())).isEqualTo(1);

        // the composite key keeps a row of one client from pointing at a listing of another
        assertThatThrownBy(() -> jdbc.update("INSERT INTO workspace_status_override (client_id, listing_id, reason, content_hash, audit_state, block_reasons) "
                + "VALUES (?, ?, 'x', ?, 'NONE', 'UNCHECKED')", stranger.client().getId(), listing.id(), listing.contentHash()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // a stranger finds no listing of this client to clone, edit or restore
        assertThat(listings.find(stranger.client().getId(), listing.id())).isEmpty();
        assertThatThrownBy(() -> editor.save(stranger.admin(), listing.id(), listing.version(), Map.of())).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("listing.unknown"));
        assertThatThrownBy(() -> history.restore(stranger.admin(), listing.id(), 0, listing.version())).isInstanceOf(WorkspaceException.class);
        assertThat(statusService.markReady(stranger.admin(), List.of(listing.id()), false, null)).extracting(ListingStatusService.Outcome::result)
                .containsExactly(ListingStatusService.Result.UNKNOWN);
    }

    @Test
    void purgeRemovesTheReasonsToo() throws IOException {
        Team team = team();
        SellerAccount account = amazonWithFamily(team, "Shop One", "E-");
        Listing listing = listing(team, account, "E-S2");
        jdbc.update("INSERT INTO workspace_status_override (client_id, listing_id, opened_by, reason, content_hash, audit_state, block_reasons) "
                + "VALUES (?, ?, ?, 'because', ?, 'NONE', 'UNCHECKED')", team.client().getId(), listing.id(), team.admin().accountId(), listing.contentHash());
        WorkspaceDataCommands commands = new WorkspaceDataCommands(jdbc, clients, mapper, transaction, new java.io.PrintStream(new java.io.ByteArrayOutputStream()));

        commands.purge(team.client(), WorkspaceDataCommands.confirmationCode(team.client().getId(), commands.counts(team.client().getId())));

        for (String table : WorkspaceDataCommands.TABLES) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE client_id = ?", Long.class, team.client().getId())).as(table).isZero();
        }
    }
}
