package com.thanhdatpb.listingguard.api.workspace;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With {@code listing-guard.workspace.enabled} left at its default (false) the workspace does not exist (ADR-059): no bean, no menu entry,
 * every path a 404, no table touched, and the pages and the check API that were there before answer as they did. The Console is on, so the
 * only thing that differs from a workspace deployment is the flag.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WorkspaceDisabledIntegrationTest {

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
    WorkspaceProperties properties;

    @MockitoBean
    JavaMailSender mailSender;

    private ConsoleUser admin() {
        Client client = provisioning.createClient("wd" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        ConsoleUser user = new ConsoleUser("admin-" + UUID.randomUUID() + "@example.test", client.getId());
        console.saveAndFlush(new ConsoleAccount(user.email(), client.getId(), ConsoleRole.ADMIN));
        return user;
    }

    @Test
    void noBeanOfTheWorkspaceExistsAndTheFlagAndTheScopeHaveTheirDefaults() {
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.memberEditScope()).isEqualTo(WorkspaceProperties.EditScope.OWN);
        for (Class<?> type : new Class<?>[] {WorkspaceListings.class, WorkspaceAccounts.class, WorkspaceTemplates.class, ImportStages.class,
                ListingImportService.class, TemplateService.class, WorkspaceAccess.class, StandardTargets.class, EtsyFields.class,
                // ADR-062: the editor, the clone, the history, the status and the conflicts of an import
                ListingContent.class, ListingEditor.class, ListingCloner.class, ListingHistory.class, ListingComparison.class,
                ListingStatusService.class, WorkspaceOverrides.class,
                com.thanhdatpb.listingguard.api.console.workspace.ConsoleWorkspaceEditorController.class,
                com.thanhdatpb.listingguard.api.console.workspace.ConsoleWorkspaceController.class,
                com.thanhdatpb.listingguard.api.console.workspace.ConsoleWorkspaceImportController.class}) {
            assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).isEmpty();
        }
    }

    @Test
    void everyWorkspacePathIsA404ForASignedInAccountAndWritesNothing() throws Exception {
        ConsoleUser user = admin();
        UUID id = UUID.randomUUID();
        for (String path : new String[] {"/console/workspace", "/console/workspace/accounts", "/console/workspace/import",
                "/console/workspace/import/sheet", "/console/workspace/import/mapping", "/console/workspace/listings/" + id,
                "/console/workspace/templates/" + id + "/file", "/console/workspace/new", "/console/workspace/new?account=" + id,
                "/console/workspace/listings/" + id + "/edit", "/console/workspace/listings/" + id + "/history", "/console/workspace/export",
                "/console/workspace/export?account=" + id}) {
            mvc.perform(get(path).with(signedIn(user))).andExpect(status().isNotFound());
        }
        for (String path : new String[] {"/console/workspace/accounts", "/console/workspace/import/preview", "/console/workspace/import/commit",
                "/console/workspace/import/save-template", "/console/workspace/import/cancel", "/console/workspace/listings/" + id + "/owner",
                "/console/workspace/listings/" + id + "/delete", "/console/workspace/new", "/console/workspace/listings/" + id + "/edit",
                "/console/workspace/clone", "/console/workspace/clone/preview", "/console/workspace/clone/commit", "/console/workspace/status",
                "/console/workspace/listings/" + id + "/restore", "/console/workspace/listings/" + id + "/draft", "/console/workspace/export",
                "/console/workspace/listings/" + id + "/posted", "/console/workspace/listings/" + id + "/live"}) {
            mvc.perform(post(path).with(signedIn(user)).with(csrf()).param("name", "x")).andExpect(status().isNotFound());
        }
        mvc.perform(multipart("/console/workspace/import/upload").file(new MockMultipartFile("file", "f.xlsx", "application/octet-stream", new byte[] {1}))
                .with(signedIn(user)).with(csrf())).andExpect(status().isNotFound());
        for (String table : WorkspaceDataCommands.TABLES) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE client_id = ?", Long.class, user.clientId())).as(table).isZero();
        }
    }

    @Test
    void thePagesThatWereThereHaveNoMenuEntryNoIconAndNoMentionOfTheWorkspace() throws Exception {
        ConsoleUser user = admin();
        for (String path : new String[] {"/console", "/console/check", "/console/bulk", "/console/history", "/console/watchlist"}) {
            String page = mvc.perform(get(path).with(signedIn(user))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(page).as(path).isNotEmpty().doesNotContainIgnoringCase("workspace").doesNotContain("lg-i-s-draft")
                    .doesNotContain("lg-i-upload").doesNotContain("lg-i-accounts").doesNotContain("/console/workspace");
        }
    }
}
