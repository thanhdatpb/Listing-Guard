package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The workspace console with {@code sheet-tools=true} and members allowed to import (ADR-064 §2): the Sheet tools are back, in the menu and on their paths. */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true", "listing-guard.console.pilot=true", "listing-guard.console.role-home-enabled=true",
        "listing-guard.console.base-url=http://localhost", "listing-guard.phrase-screening.enabled=true",
        "listing-guard.console.mode=workspace", "listing-guard.console.sheet-tools=true",
        "listing-guard.workspace.enabled=true", "listing-guard.workspace.import-roles=ADMIN,MEMBER",
        "listing-guard.workspace.import-max-bytes=64KB", "listing-guard.workspace.max-template-bytes=64KB"
})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleWorkspaceModeSheetToolsTest extends ConsoleWorkspaceModeBase {

    @Test
    void theSheetToolsComeBackInTheMenuAndOnTheirPaths() throws Exception {
        Team team = team();
        List<String> admin = menu(page(team.admin(), "/console", "vi"));
        assertThat(admin).containsSubsequence("/console", "/console/workspace", "/console/check", "/console/bulk", "/console/check/edit",
                "/console/history", "/console/reaudit", "/console/workspace/accounts");
        // the experiments are where they were
        assertThat(admin).endsWith("/console/policy-changes", "/console/pod-readiness");
        for (String path : List.of("/console/bulk", "/console/check/edit", "/console/reaudit", "/console/history")) {
            assertThat(status(team.admin(), path)).as(path).isEqualTo(200);
            assertThat(status(team.member(), path)).as(path + " (member)").isEqualTo(200);
        }
        String home = page(team.member(), "/console", "vi");
        assertThat(home).contains("id=\"home-workspace\"", "id=\"pilot-start\"", "href=\"/console/check/edit\"");
    }

    @Test
    void membersImportWhenTheSettingNamesThem() throws Exception {
        Team team = team();
        assertThat(status(team.member(), "/console/workspace/import")).isEqualTo(200);
        assertThat(menu(page(team.member(), "/console", "vi"))).contains("/console/workspace/import");
        // naming MEMBER does not give a member the management of accounts and templates
        assertThat(status(team.member(), "/console/workspace/import?mode=template")).isEqualTo(403);
        assertThat(menu(page(team.member(), "/console", "vi"))).doesNotContain("/console/workspace/accounts", "/console/watchlist");
    }
}
