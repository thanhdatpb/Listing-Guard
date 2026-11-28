package com.thanhdatpb.listingguard.api.workspace;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.SourceFile;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import com.thanhdatpb.listingguard.workspace.sheets.SheetReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The export screen of the text for a Sheet with a listing whose cell starts with "=" (ADR-064 §5); the console mode comes from the subclass. */
abstract class WorkspaceConsoleExportEscapeBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    ClientProvisioning provisioning;
    @Autowired
    ConsoleAccountRepository console;
    @Autowired
    WorkspaceAccess access;
    @Autowired
    WorkspaceAccounts accounts;
    @Autowired
    ListingImportService imports;
    @Autowired
    ListingStatusService statusService;
    @Autowired
    WorkspaceProperties properties;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    JavaMailSender mailSender;

    record Setup(ConsoleUser admin, SellerAccount account, UUID listing) {
    }

    Setup setup() throws IOException {
        Client client = provisioning.createClient("wm" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        ConsoleUser admin = new ConsoleUser("admin-" + UUID.randomUUID() + "@example.test", client.getId());
        console.saveAndFlush(new ConsoleAccount(admin.email(), client.getId(), ConsoleRole.ADMIN));
        Actor actor = access.actor(admin).orElseThrow();
        SellerAccount account = accounts.create(client.getId(), Marketplace.AMAZON, "Shop Mode", null, ExportFormat.TXT);
        byte[] file = ExportFixtures.flatFileXlsm("M-");
        SheetGrid grid = SheetReader.read(file, "Template", properties.limits(false));
        var view = imports.mappingView(actor, account.id(), TemplateKind.AMAZON_BULK, grid, 3);
        Map<Integer, String> targets = new LinkedHashMap<>();
        view.columns().forEach(c -> {
            if (!c.target().isEmpty()) {
                targets.put(c.index(), c.target());
            }
        });
        imports.commit(actor, new Params(account.id(), TemplateKind.AMAZON_BULK, actor.accountId(), ListingStatus.DRAFT, "Template", 3, 4, targets,
                Map.of(), Set.of(), Set.of()), grid, new SourceFile(SheetReader.detect(file, properties.limits(false)), "template-file", file), null);
        List<UUID> ids = jdbc.queryForList("SELECT id FROM workspace_listing WHERE client_id = ? AND account_id = ? ORDER BY sku", UUID.class,
                client.getId(), account.id());
        statusService.markReady(actor, ids, false, null);
        UUID first = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE account_id = ? AND sku = 'M-S2'", UUID.class, account.id());
        jdbc.update("UPDATE workspace_listing SET data = jsonb_set(data, '{item_name}', '\"=HYPERLINK(1)\"') WHERE id = ?", first);
        return new Setup(admin, account, first);
    }

    MvcResult send(Setup setup, String step, String... more) throws Exception {
        var request = post("/console/workspace/export").with(signedIn(setup.admin())).with(csrf()).param("account", setup.account().id().toString())
                .param("listing", setup.listing().toString()).param("medium", "CLIPBOARD").param("step", step);
        if ("export".equals(step)) {
            request.param("confirm", "1");
        }
        for (int i = 0; i < more.length; i += 2) {
            request.param(more[i], more[i + 1]);
        }
        return mvc.perform(request).andReturn();
    }

    static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
