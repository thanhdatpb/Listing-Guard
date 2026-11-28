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
 * {@code mode=workspace} with the listing workspace switched off (ADR-064 §1): the console stays classic. Its pages are the classic golden
 * files of the console without a workspace ({@link ConsoleClassicPlainGoldenTest}), and the Sheet paths are reachable.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true", "listing-guard.console.base-url=http://localhost",
        "listing-guard.console.mode=workspace", "listing-guard.workspace.enabled=false"
})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleWorkspaceModeWithoutWorkspaceTest extends ConsoleClassicGoldenBase {

    @Override
    String group() {
        return "plain";
    }

    @Test
    void theConsoleStaysClassicByteForByte() throws Exception {
        everyPage();
    }

    @Test
    void theSheetPathsAreReachable() throws Exception {
        Team team = team();
        for (String path : List.of("/console/bulk", "/console/check/edit", "/console/reaudit", "/console/history", "/console/check")) {
            assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .with(ConsoleTestSupport.signedIn(team.admin()))).andReturn().getResponse().getStatus()).as(path).isEqualTo(200);
        }
    }
}
