package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The menu of the classic console in a browser (ADR-064); see {@link ConsoleMenuBrowserBase}. */
@EnabledIfEnvironmentVariable(named = "LG_RUN_WORKSPACE_BROWSER", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.default-locale=en",
        "listing-guard.console.cookie-secure=false",
        "listing-guard.console.base-url=http://localhost",
        "listing-guard.console.auth-mode=cloudflare-access",
        "listing-guard.console.access-team-domain=https://example.cloudflareaccess.com",
        "listing-guard.console.access-aud=workspace-test",
        "listing-guard.console.access-allowed-domains=example.test",
        "listing-guard.console.access-auto-create=false",
        "listing-guard.console.pilot=true",
        "listing-guard.console.role-home-enabled=true",
        "listing-guard.console.policy-changes-enabled=false",
        "listing-guard.console.pod-enabled=false",
        "listing-guard.console.mode=classic",
        "listing-guard.demo.enabled=true",
        "listing-guard.workspace.enabled=true",
        "listing-guard.workspace.audit.enabled=true",
        "listing-guard.workspace.audit.auto-start=false",
        "listing-guard.workspace.alerts.email.enabled=true",
        "spring.servlet.multipart.max-file-size=25MB",
        "spring.servlet.multipart.max-request-size=60MB"
})
@ActiveProfiles({"test", "dev"})
@Import(TestcontainersConfig.class)
class ConsoleMenuClassicBrowserIntegrationTest extends ConsoleMenuBrowserBase {

    @Override
    String mode() {
        return "classic";
    }
}
