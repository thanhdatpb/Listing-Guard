package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The classic console with no pilot and no workspace (ADR-064): the menu and the home do not change. */
@SpringBootTest(properties = {"listing-guard.console.enabled=true", "listing-guard.console.base-url=http://localhost"})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleClassicPlainGoldenTest extends ConsoleClassicGoldenBase {

    @Override
    String group() {
        return "plain";
    }

    @Test
    void theMenuAndTheHomeAreThoseOfMain() throws Exception {
        everyPage();
    }
}
