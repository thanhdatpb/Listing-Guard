package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The classic console keeps the text for a Sheet as it was: no option is offered and no cell is changed (ADR-064 §5). */
@SpringBootTest(properties = {
        "listing-guard.workspace.enabled=true", "listing-guard.workspace.block-on-error=false",
        "spring.servlet.multipart.max-file-size=25MB", "spring.servlet.multipart.max-request-size=60MB",
        "listing-guard.console.enabled=true", "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test", "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WorkspaceConsoleExportClassicTest extends WorkspaceConsoleExportEscapeBase {

    @Test
    void noOptionIsOfferedAndNoCellIsChanged() throws Exception {
        Setup setup = setup();
        assertThat(body(send(setup, "preview"))).doesNotContain("export-escape", "escapeShown");
        String pasted = body(send(setup, "export"));
        assertThat(pasted).contains("\t=HYPERLINK(1)\t").doesNotContain("&#39;=HYPERLINK").doesNotContain("paste-escaped");
    }

    @Test
    void aFormThatSendsTheOptionStillGetsTheOptionBecauseTheCodeHonoursIt() throws Exception {
        Setup setup = setup();
        assertThat(body(send(setup, "export", "escapeShown", "1", "escape", "1"))).contains("&#39;=HYPERLINK(1)");
    }
}
