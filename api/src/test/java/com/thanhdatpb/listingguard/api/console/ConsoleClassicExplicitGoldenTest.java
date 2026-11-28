package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** {@code mode=classic} said out loud is the same console as no mode at all (ADR-064): the golden files of {@link ConsoleClassicWorkspaceGoldenTest}. */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true", "listing-guard.console.pilot=true", "listing-guard.console.role-home-enabled=true",
        "listing-guard.console.base-url=http://localhost", "listing-guard.phrase-screening.enabled=true",
        "listing-guard.console.mode=classic",
        "listing-guard.workspace.enabled=true", "listing-guard.workspace.audit.enabled=true",
        "listing-guard.workspace.import-max-bytes=64KB", "listing-guard.workspace.max-template-bytes=64KB"
})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleClassicExplicitGoldenTest extends ConsoleClassicGoldenBase {

    @Override
    String group() {
        return "workspace";
    }

    @Test
    void theMenuAndTheHomeAreThoseOfMain() throws Exception {
        everyPage();
    }
}
