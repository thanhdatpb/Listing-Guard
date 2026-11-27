package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets.Kind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceProperties.EditScope;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

/** The small rules of the workspace that need no database: the permission matrix, the settings, the YAML lists, the schema and the commands. */
class WorkspaceRulesTest {

    // ---------------------------------------------------------------- permissions (ADR-059)

    @Test
    void aMemberChangesOnlyTheirOwnListingsUnlessTheScopeIsAllAndAnAdminChangesEverything() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Actor member = new Actor(me, UUID.randomUUID(), "m@example.test", false);
        Actor admin = new Actor(UUID.randomUUID(), member.clientId(), "a@example.test", true);
        WorkspaceAccess own = new WorkspaceAccess(Mockito.mock(ConsoleAccountRoles.class), PlanFixtures.properties(EditScope.OWN));
        WorkspaceAccess all = new WorkspaceAccess(Mockito.mock(ConsoleAccountRoles.class), PlanFixtures.properties(EditScope.ALL));

        assertThat(own.canEdit(member, me)).isTrue();
        assertThat(own.canEdit(member, other)).isFalse();
        assertThat(own.canEdit(member, null)).isFalse();
        assertThat(all.canEdit(member, other)).isTrue();
        assertThat(all.canEdit(member, null)).isTrue();
        assertThat(own.canEdit(admin, other)).isTrue();
        assertThat(own.canEdit(admin, null)).isTrue();
        // managing accounts and templates, the responsible person and deleting never depend on the scope
        assertThat(own.canManage(admin)).isTrue();
        assertThat(all.canManage(member)).isFalse();
    }

    @Test
    void theRoleComesFromTheDatabaseAndAnAccountOfAnotherClientHasNoAccess() {
        ConsoleAccountRoles roles = Mockito.mock(ConsoleAccountRoles.class);
        UUID client = UUID.randomUUID();
        ConsoleAccount admin = new ConsoleAccount("admin@example.test", client, ConsoleRole.ADMIN);
        Mockito.when(roles.account(new ConsoleUser("admin@example.test", client))).thenReturn(Optional.of(admin));
        Mockito.when(roles.account(new ConsoleUser("admin@example.test", UUID.randomUUID()))).thenReturn(Optional.empty());
        WorkspaceAccess access = new WorkspaceAccess(roles, PlanFixtures.properties(EditScope.OWN));

        assertThat(access.actor(new ConsoleUser("admin@example.test", client))).hasValueSatisfying(a -> {
            assertThat(a.admin()).isTrue();
            assertThat(a.clientId()).isEqualTo(client);
        });
        assertThat(access.actor(new ConsoleUser("admin@example.test", UUID.randomUUID()))).isEmpty();
    }

    // ---------------------------------------------------------------- settings

    @Test
    void theWorkspaceIsOffByDefaultAndOwnIsTheDefaultScope() throws IOException {
        // the defaults of the record (no property set) and of application.yml
        WorkspaceProperties bound = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("listing-guard.workspace", WorkspaceProperties.class);
        assertThat(bound.enabled()).isFalse();
        assertThat(bound.memberEditScope()).isEqualTo(EditScope.OWN);
        assertThat(bound.importMaxRows()).isEqualTo(10_000);
        assertThat(bound.importMaxBytes()).isEqualTo(DataSize.ofMegabytes(20));
        assertThat(bound.maxTemplateBytes()).isEqualTo(DataSize.ofMegabytes(10));
        assertThat(bound.historyKeep()).isEqualTo(20);
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertThat(yaml).contains("enabled: ${LISTING_GUARD_WORKSPACE_ENABLED:false}")
                .contains("member-edit-scope: ${LISTING_GUARD_WORKSPACE_MEMBER_EDIT_SCOPE:own}");
    }

    @Test
    void theLimitsTheReaderGetsAreTheConfiguredOnes() {
        WorkspaceProperties properties = PlanFixtures.properties(EditScope.OWN);

        assertThat(properties.limits(false).maxBytes()).isEqualTo(20L << 20);
        assertThat(properties.limits(true).maxBytes()).isEqualTo(10L << 20);
        assertThat(properties.limits(false).maxRows()).isEqualTo(10_000 + WorkspaceProperties.HEADER_ALLOWANCE);
        assertThat(properties.limits(false).minInflateRatio()).isEqualTo(0.01);
        assertThatThrownBy(() -> new WorkspaceProperties(true, EditScope.OWN, DataSize.ofMegabytes(1), DataSize.ofMegabytes(1), 0, 1, 1,
                Duration.ofHours(1), 0.01, DataSize.ofMegabytes(1), DataSize.ofMegabytes(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theApplicationRefusesToStartWhenAnImportCouldBeLargerThanTheUploadLimit() {
        WorkspaceConfig config = new WorkspaceConfig();
        WorkspaceProperties properties = PlanFixtures.properties(EditScope.OWN);

        assertThatThrownBy(() -> config.workspaceStartupCheck(properties, new MockEnvironment()
                .withProperty("spring.servlet.multipart.max-file-size", "10MB"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LISTING_GUARD_MULTIPART_MAX_FILE_SIZE");
        assertThat(config.workspaceStartupCheck(properties, new MockEnvironment().withProperty("spring.servlet.multipart.max-file-size", "25MB")))
                .isNotNull();
    }

    // ---------------------------------------------------------------- the YAML lists (unverified guesses)

    @Test
    void theGuessesAreUnverifiedAndOnlyFillInAProposal() {
        StandardTargets targets = StandardTargets.load();

        assertThat(targets.guess(Marketplace.AMAZON, "item_sku")).hasValueSatisfying(t -> assertThat(t.kind()).isEqualTo(Kind.SKU));
        assertThat(targets.guess(Marketplace.AMAZON, "Seller SKU")).hasValueSatisfying(t -> assertThat(t.key()).isEqualTo("sku"));
        assertThat(targets.guess(Marketplace.AMAZON, "bullet_point[marketplace_id=X]#1.value")).hasValueSatisfying(t -> assertThat(t.key()).isEqualTo("bullet_points"));
        assertThat(targets.guess(Marketplace.AMAZON, "Tiêu đề")).hasValueSatisfying(t -> assertThat(t.key()).isEqualTo("title"));
        assertThat(targets.guess(Marketplace.AMAZON, "feed_product_type")).hasValueSatisfying(t -> assertThat(t.key()).isEqualTo("product_type"));
        assertThat(targets.guess(Marketplace.AMAZON, "totally_unknown_column")).isEmpty();
        // tags are an Etsy field and search terms an Amazon one: the same word means the field of the marketplace
        assertThat(targets.guess(Marketplace.ETSY, "tags")).hasValueSatisfying(t -> assertThat(t.key()).isEqualTo("tags"));
        assertThat(targets.guess(Marketplace.ETSY, "TAG7")).hasValueSatisfying(t -> assertThat(t.key()).isEqualTo("tags"));
        assertThat(targets.guess(Marketplace.AMAZON, "keywords")).hasValueSatisfying(t -> assertThat(t.key()).isEqualTo("search_terms"));
        assertThat(targets.isParentValue(" Parent ")).isTrue();
        assertThat(targets.isChildValue("CHILD")).isTrue();
        assertThat(targets.isChildValue("variation")).isFalse();
        assertThat(StandardTargets.normalize("  Bullet Point 1 ")).isEqualTo("bulletpoint1");
    }

    @Test
    void theEtsyFieldListIsUnverifiedFixedAndHoldsTheMainFields() {
        EtsyFields etsy = EtsyFields.load();
        StandardTargets targets = StandardTargets.load();

        assertThat(etsy.fields()).contains("title", "description", "tags", "materials", "personalization", "price", "quantity", "sku");
        // every Etsy field is a standard target of the marketplace, so a listing can be checked later without another mapping
        etsy.fields().forEach(field -> assertThat(targets.find(Marketplace.ETSY, field)).as(field).isPresent());
    }

    // ---------------------------------------------------------------- the schema equals the enums

    @Test
    void everyCheckConstraintOfTheMigrationEqualsItsJavaEnum() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V57__listing_workspace.sql"), StandardCharsets.UTF_8);

        assertThat(constraint(sql, "ck_workspace_account_marketplace")).containsExactlyElementsOf(names(Marketplace.values()));
        assertThat(constraint(sql, "ck_workspace_account_export")).containsExactlyElementsOf(names(WorkspaceTypes.ExportFormat.values()));
        assertThat(constraint(sql, "ck_workspace_template_kind")).containsExactlyElementsOf(names(WorkspaceTypes.TemplateKind.values()));
        assertThat(constraint(sql, "ck_workspace_listing_role")).containsExactlyElementsOf(names(WorkspaceTypes.ListingRole.values()));
        assertThat(constraint(sql, "ck_workspace_listing_status")).containsExactlyElementsOf(names(WorkspaceTypes.ListingStatus.values()));
        assertThat(constraint(sql, "ck_workspace_listing_source")).containsExactlyElementsOf(names(WorkspaceTypes.ListingSource.values()));
        assertThat(constraint(sql, "ck_workspace_history_kind")).containsExactlyElementsOf(names(WorkspaceTypes.ChangeKind.values()));
        assertThat(constraint(sql, "ck_workspace_template_format")).contains("XLSX", "XLSM", "TSV", "CSV", "BUILTIN");
    }

    @Test
    void everyTableOfTheMigrationIsAdditiveAndEveryChildPointsToItsParentThroughTheClient() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V57__listing_workspace.sql"), StandardCharsets.UTF_8);

        assertThat(sql).doesNotContainIgnoringCase("ALTER TABLE").doesNotContainIgnoringCase("DROP ");
        Matcher tables = Pattern.compile("CREATE TABLE (workspace_\\w+)").matcher(sql);
        List<String> created = new ArrayList<>();
        while (tables.find()) {
            created.add(tables.group(1));
        }
        assertThat(created).containsExactlyInAnyOrderElementsOf(WorkspaceDataCommands.TABLES);
        // each table but the account has a client_id column, so every statement and the purge can filter by it
        created.forEach(table -> assertThat(sql.substring(sql.indexOf("CREATE TABLE " + table))).contains("client_id"));
        assertThat(sql).contains("REFERENCES workspace_account (id, client_id)").contains("REFERENCES workspace_template (id, client_id)")
                .contains("REFERENCES workspace_listing (id, client_id)");
    }

    private static List<String> constraint(String sql, String name) {
        Matcher match = Pattern.compile("CONSTRAINT " + name + " CHECK \\((?:\\w+) IN \\(([^)]*)\\)").matcher(sql);
        assertThat(match.find()).as(name).isTrue();
        List<String> values = new ArrayList<>();
        Matcher value = Pattern.compile("'([A-Z0-9_]+)'").matcher(match.group(1));
        while (value.find()) {
            values.add(value.group(1));
        }
        return values;
    }

    private static List<String> names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).toList();
    }

    // ---------------------------------------------------------------- the commands

    @Test
    void theConfirmationCodeFollowsTheCountsSoStaleDataNeedsANewLook() {
        UUID client = UUID.randomUUID();
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        WorkspaceDataCommands.TABLES.forEach(t -> counts.put(t, 3L));

        String code = WorkspaceDataCommands.confirmationCode(client, counts);
        assertThat(code).hasSize(8).isEqualTo(WorkspaceDataCommands.confirmationCode(client, counts));
        counts.put("workspace_listing", 4L);
        assertThat(WorkspaceDataCommands.confirmationCode(client, counts)).isNotEqualTo(code);
        assertThat(WorkspaceDataCommands.confirmationCode(UUID.randomUUID(), counts)).isNotEqualTo(WorkspaceDataCommands.confirmationCode(client, counts));
    }

    @Test
    void anExportedRowReadsBackAsItWasWrittenEvenWithTabsQuotesAndLineBreaks() {
        StringBuilder out = new StringBuilder();
        WorkspaceDataCommands.appendRow(out, List.of("plain", "has\ttab", "has \"quote\"", "line1\nline2", ""));

        assertThat(out.toString()).isEqualTo("plain\t\"has\ttab\"\t\"has \"\"quote\"\"\"\t\"line1\nline2\"\t\n");
    }

    @Test
    void thePurgeDeletesNothingOutsideTheWorkspaceTables() {
        assertThat(WorkspaceDataCommands.TABLES).allSatisfy(table -> assertThat(table).startsWith("workspace_"));
        // children first, so a foreign key never blocks the delete
        assertThat(WorkspaceDataCommands.TABLES.indexOf("workspace_listing_history")).isLessThan(WorkspaceDataCommands.TABLES.indexOf("workspace_listing"));
        assertThat(WorkspaceDataCommands.TABLES.indexOf("workspace_listing")).isLessThan(WorkspaceDataCommands.TABLES.indexOf("workspace_template"));
        assertThat(WorkspaceDataCommands.TABLES.indexOf("workspace_template")).isLessThan(WorkspaceDataCommands.TABLES.indexOf("workspace_account"));
    }

    // ---------------------------------------------------------------- the account columns and keys

    @Test
    void columnKeysKeepRepeatsAndBlankHeadersApartWithoutLosingTheHeaderText() {
        var columns = WorkspaceTemplates.columnsOf(List.of("item_sku", "tag", "tag", "", " item_name "), 6);

        assertThat(columns).extracting(c -> c.key()).containsExactly("item_sku", "tag", "tag~2", "col_D", "item_name", "col_F");
        assertThat(columns.get(4).header()).isEqualTo(" item_name ");
        assertThat(WorkspaceTemplates.letters(0)).isEqualTo("A");
        assertThat(WorkspaceTemplates.letters(25)).isEqualTo("Z");
        assertThat(WorkspaceTemplates.letters(26)).isEqualTo("AA");
        assertThat(WorkspaceTemplates.letters(701)).isEqualTo("ZZ");
        assertThat(WorkspaceTemplates.letters(702)).isEqualTo("AAA");
        // the identity of a version is the exact header: a different order or spelling is another hash
        assertThat(WorkspaceTemplates.headerHash(WorkspaceTemplates.columnsOf(List.of("a", "b"), 2)))
                .isNotEqualTo(WorkspaceTemplates.headerHash(WorkspaceTemplates.columnsOf(List.of("b", "a"), 2)));
    }
}
