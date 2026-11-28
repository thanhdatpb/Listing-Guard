package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The classic console of a pilot with the listing workspace on and the console mode left alone (ADR-064): the menu and the home do not change. */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true", "listing-guard.console.pilot=true", "listing-guard.console.role-home-enabled=true",
        "listing-guard.console.base-url=http://localhost", "listing-guard.phrase-screening.enabled=true",
        "listing-guard.workspace.enabled=true", "listing-guard.workspace.audit.enabled=true",
        "listing-guard.workspace.import-max-bytes=64KB", "listing-guard.workspace.max-template-bytes=64KB"
})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleClassicWorkspaceGoldenTest extends ConsoleClassicGoldenBase {

    @Override
    String group() {
        return "workspace";
    }

    @Test
    void theMenuAndTheHomeAreThoseOfMain() throws Exception {
        everyPage();
    }
}
