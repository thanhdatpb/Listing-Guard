package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The workspace console of a pilot (ADR-064): the menu by role and in both languages, the Sheet tools hidden (no entry, no link on a page that
 * is left, 404 on the path), the single check kept as "Quick check", the import for ADMIN only, the member's home with the workspace shortcuts.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true", "listing-guard.console.pilot=true", "listing-guard.console.role-home-enabled=true",
        "listing-guard.console.base-url=http://localhost", "listing-guard.phrase-screening.enabled=true",
        "listing-guard.console.mode=workspace",
        "listing-guard.console.policy-changes-enabled=false", "listing-guard.console.pod-enabled=false",
        "listing-guard.workspace.enabled=true", "listing-guard.workspace.audit.enabled=true",
        "listing-guard.workspace.import-max-bytes=64KB", "listing-guard.workspace.max-template-bytes=64KB"
})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleWorkspaceModeTest extends ConsoleWorkspaceModeBase {

    @Test
    void anAdminSeesTheWorkspaceMenuInTheOrderOfTheDecision() throws Exception {
        Team team = team();
        String vi = page(team.admin(), "/console", "vi");
        assertThat(menu(vi)).containsExactly("/console", "/console/workspace", "/console/workspace/new", "/console/workspace/export",
                "/console/phrase-screening", "/console/check", "/console/workspace/audit", "/console/workspace/accounts",
                "/console/workspace/import", "/console/watchlist", "/console/stats", "/console/stats/pilot", "/console/readiness",
                "/console/api-keys");
        assertThat(groups(vi)).containsExactly("Kho listing", "Công cụ", "Theo dõi", "Quản lý");
        assertThat(vi).contains("Kiểm nhanh", "Tạo mới", "Xuất file", "Tài khoản &amp; template", "Rà nền");
        String en = page(team.admin(), "/console", "en");
        assertThat(menu(en)).isEqualTo(menu(vi));
        assertThat(groups(en)).containsExactly("Listing workspace", "Tools", "Monitor", "Manage");
        assertThat(en).contains("Quick check", "Create new", "Export file");
    }

    @Test
    void aMemberSeesNoManagementAndNoImport() throws Exception {
        Team team = team();
        for (String language : List.of("vi", "en")) {
            String html = page(team.member(), "/console", language);
            assertThat(menu(html)).containsExactly("/console", "/console/workspace", "/console/workspace/new", "/console/workspace/export",
                    "/console/phrase-screening", "/console/check", "/console/workspace/audit");
            assertThat(html).doesNotContain("/console/workspace/import", "/console/workspace/accounts", "/console/watchlist");
        }
        assertThat(groups(page(team.member(), "/console", "vi"))).containsExactly("Kho listing", "Công cụ", "Theo dõi");
    }

    @Test
    void theSheetToolsAreNotInAnyMenuAndTheirPathsAnswer404() throws Exception {
        Team team = team();
        for (var user : List.of(team.admin(), team.member())) {
            assertThat(status(user, "/console/bulk")).isEqualTo(404);
            assertThat(status(user, "/console/bulk/prepare")).isEqualTo(404);
            assertThat(status(user, "/console/check/edit")).isEqualTo(404);
            assertThat(status(user, "/console/reaudit")).isEqualTo(404);
            assertThat(status(user, "/console/history")).isEqualTo(404);
            assertThat(status(user, "/console/history/listing-rules/" + java.util.UUID.randomUUID())).isEqualTo(404);
            assertThat(postStatus(user, "/console/bulk/prepare")).isEqualTo(404);
            assertThat(postStatus(user, "/console/bulk/run")).isEqualTo(404);
            assertThat(postStatus(user, "/console/reaudit/run")).isEqualTo(404);
            assertThat(postStatus(user, "/console/check/edit")).isEqualTo(404);
            assertThat(postStatus(user, "/console/check/edit/policy-checks")).isEqualTo(404);
            // the single check stays, as "Quick check"
            assertThat(status(user, "/console/check")).isEqualTo(200);
        }
    }

    @Test
    void noPageOfTheWorkspaceConsoleLinksToAHiddenPath() throws Exception {
        Team team = team();
        List<String> paths = List.of("/console", "/console/check", "/console/readiness", "/console/stats", "/console/stats/pilot",
                "/console/watchlist", "/console/api-keys", "/console/workspace", "/console/workspace/new", "/console/workspace/export",
                "/console/phrase-screening", "/console/workspace/audit", "/console/workspace/accounts", "/console/workspace/import");
        for (String language : List.of("vi", "en")) {
            for (String path : paths) {
                String html = page(team.admin(), path, language);
                assertThat(SHEET_LINK.matcher(html).find()).as(path + " " + language + " links to a hidden path").isFalse();
            }
            for (String path : List.of("/console", "/console/check", "/console/workspace", "/console/workspace/new", "/console/phrase-screening")) {
                String html = page(team.member(), path, language);
                assertThat(SHEET_LINK.matcher(html).find()).as(path + " " + language + " (member) links to a hidden path").isFalse();
            }
        }
    }

    @Test
    void theMembersHomeHasWorkspaceShortcutsInsteadOfTheSheetButtons() throws Exception {
        Team team = team();
        String vi = page(team.member(), "/console", "vi");
        assertThat(vi).contains("id=\"home-workspace\"", "id=\"home-new\"", "id=\"home-phrase-screening\"", "Mở Kho listing và chọn listing của bạn.")
                .doesNotContain("id=\"pilot-start\"", "Dán các dòng từ Sheet", "href=\"/console/check/edit\"");
        String en = page(team.member(), "/console", "en");
        assertThat(en).contains("id=\"home-workspace\"", "Create new", "Open the listing workspace and pick your listing.");
    }

    @Test
    void theManagersHomeAndReadinessDoNotLinkToHiddenPaths() throws Exception {
        Team team = team();
        String home = page(team.admin(), "/console", "vi");
        String readiness = page(team.admin(), "/console/readiness", "vi");
        assertThat(SHEET_LINK.matcher(home).find()).isFalse();
        assertThat(SHEET_LINK.matcher(readiness).find()).isFalse();
        assertThat(home).contains("Tổng quan");
    }

    @Test
    void onlyAnAdminImportsByDefault() throws Exception {
        Team team = team();
        assertThat(status(team.admin(), "/console/workspace/import")).isEqualTo(200);
        assertThat(status(team.member(), "/console/workspace/import")).isEqualTo(403);
        assertThat(status(team.member(), "/console/workspace/import/sheet")).isEqualTo(403);
        assertThat(postStatus(team.member(), "/console/workspace/import/upload")).isEqualTo(403);
        // the listing pages stay open to a member
        assertThat(status(team.member(), "/console/workspace")).isEqualTo(200);
    }

    @Test
    void theNavigationOfTheCurrentPageIsMarked() throws Exception {
        Team team = team();
        assertThat(page(team.admin(), "/console/workspace/new", "vi")).containsPattern(
                "href=\"/console/workspace/new\" aria-current=\"page\"").doesNotContain("href=\"/console/workspace\" aria-current=\"page\"");
        assertThat(page(team.admin(), "/console/workspace/export", "vi")).contains("href=\"/console/workspace/export\" aria-current=\"page\"");
        assertThat(page(team.admin(), "/console/workspace", "vi")).contains("href=\"/console/workspace\" aria-current=\"page\"");
    }
}
