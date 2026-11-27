package com.thanhdatpb.listingguard.api.workspace.audit;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.TemplateService;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccounts;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceEvents;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ChangeKind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingSource;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With the audit off (its default) the workspace is what W1 made it (ADR-061, ADR-059 §1): no bean of the audit exists, its pages are 404,
 * the list and the detail say "not checked" as before, an import queues nothing, nothing is read from the audit's tables, and even with the
 * e-mail flag set no mail is sent. Every client and listing is synthetic.
 */
@SpringBootTest(properties = {
        "listing-guard.workspace.enabled=true",
        "listing-guard.workspace.import-max-bytes=64KB",
        "listing-guard.workspace.max-template-bytes=64KB",
        "spring.servlet.multipart.max-file-size=25MB",
        "spring.servlet.multipart.max-request-size=60MB",
        "listing-guard.workspace.alerts.email.enabled=true",
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WorkspaceAuditDisabledIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ApplicationContext context;
    @Autowired
    ClientProvisioning provisioning;
    @Autowired
    ConsoleAccountRepository console;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    WorkspaceAccounts accounts;
    @Autowired
    WorkspaceListings listings;
    @Autowired
    TemplateService templateService;
    @Autowired
    ApplicationEventPublisher events;

    @MockitoBean
    JavaMailSender mailSender;

    @Test
    void noBeanOfTheAuditExistsEvenWithTheMailFlagSet() {
        for (Class<?> type : List.of(AuditStore.class, AuditWorker.class, AuditRunner.class, AuditTriggers.class, AuditContexts.class,
                ListingAuditor.class, WorkspaceAudit.class, AlertService.class, AlertMailer.class, AuditProperties.class,
                com.thanhdatpb.listingguard.api.console.workspace.ConsoleWorkspaceAuditController.class,
                com.thanhdatpb.listingguard.api.console.workspace.WorkspaceAuditViews.class,
                com.thanhdatpb.listingguard.api.console.workspace.AuditHomeCard.class)) {
            assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).isEmpty();
        }
    }

    private ConsoleUser admin() {
        Client client = provisioning.createClient("wd" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        ConsoleUser admin = new ConsoleUser("admin-" + UUID.randomUUID() + "@example.test", client.getId());
        console.saveAndFlush(new ConsoleAccount(admin.email(), client.getId(), ConsoleRole.ADMIN));
        return admin;
    }

    private Listing listing(ConsoleUser admin) {
        SellerAccount shop = accounts.create(admin.clientId(), Marketplace.ETSY, "Shop D", "Nobrand", ExportFormat.XLSM);
        Template template = templateService.ensureEtsy(admin.clientId(), shop.id());
        var fields = mapper.createObjectNode();
        fields.put("title", "Brimwick Otters sticker");
        UUID id = UUID.randomUUID();
        Listing listing = new Listing(id, admin.clientId(), shop.id(), template.id(), "D-1", null, ListingRole.STANDALONE, "Brimwick Otters sticker",
                new LinkedHashMap<>(Map.of("title", "Brimwick Otters sticker")), fields, "0".repeat(64), null, ListingStatus.DRAFT,
                ListingSource.IMPORT, null, 0, null, null, null);
        listings.insertAll(admin.clientId(), List.of(new WorkspaceListings.Write(id, listing, ChangeKind.IMPORT, null, 0)));
        return listings.find(admin.clientId(), id).orElseThrow();
    }

    private String html(ConsoleUser user, String path) throws Exception {
        var result = mvc.perform(get(path).with(signedIn(user))).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void theAuditPagesAreNotFoundAndTheWorkspacePagesAreWhatTheyWere() throws Exception {
        ConsoleUser admin = admin();
        Listing listing = listing(admin);

        for (String path : List.of("/console/workspace/audit", "/console/workspace/audit/progress")) {
            assertThat(mvc.perform(get(path).with(signedIn(admin))).andReturn().getResponse().getStatus()).as(path).isEqualTo(404);
        }
        for (String path : List.of("/console/workspace/audit/accounts/" + listing.accountId(), "/console/workspace/audit/email",
                "/console/workspace/audit/listings/" + listing.id() + "/ack")) {
            assertThat(mvc.perform(post(path).with(signedIn(admin)).with(csrf())).andReturn().getResponse().getStatus()).as(path).isEqualTo(404);
        }
        String list = html(admin, "/console/workspace?check=NEW_RISK");
        String detail = html(admin, "/console/workspace/listings/" + listing.id());
        String home = html(admin, "/console");
        for (String page : List.of(list, detail, home)) {
            assertThat(page).doesNotContain("lg-chip--audit").doesNotContain("/console/workspace/audit").doesNotContain("lg-i-risk")
                    .doesNotContain("lg-i-audit").doesNotContain("audit-home-card").doesNotContain("audit-content");
        }
        // the filter parameter is ignored (no filter, no join): the listing is there, with the state of before
        assertThat(list).contains("D-1").contains("lg-i-unchecked").contains("Not checked").doesNotContain("name=\"check\"");
        assertThat(detail).contains("lg-i-unchecked").contains("Not checked");
    }

    @Test
    void anImportOrASaveQueuesNothingAndNothingIsReadFromOrWrittenToTheAuditTablesAndNoMailIsSent() {
        ConsoleUser admin = admin();
        Listing listing = listing(admin);

        events.publishEvent(new WorkspaceEvents.ListingsImported(admin.clientId(), listing.accountId(), List.of(listing.id()), null));
        events.publishEvent(new WorkspaceEvents.ListingSaved(admin.clientId(), listing.id()));

        for (String table : List.of("workspace_audit_run", "workspace_listing_audit", "workspace_listing_finding", "workspace_alert_log",
                "workspace_alert_pref")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE client_id = ?", Long.class, admin.clientId())).as(table).isZero();
        }
        assertThat(mockingDetails(mailSender).getInvocations()).isEmpty();
    }
}
