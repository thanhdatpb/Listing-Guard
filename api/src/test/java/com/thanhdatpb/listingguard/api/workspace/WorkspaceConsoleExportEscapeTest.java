package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The workspace console escapes the cells of the text for a Sheet by default and lets the person untick it (ADR-064 §5). */
@SpringBootTest(properties = {
        "listing-guard.workspace.enabled=true", "listing-guard.workspace.block-on-error=false", "listing-guard.console.mode=workspace",
        "spring.servlet.multipart.max-file-size=25MB", "spring.servlet.multipart.max-request-size=60MB",
        "listing-guard.console.enabled=true", "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test", "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WorkspaceConsoleExportEscapeTest extends WorkspaceConsoleExportEscapeBase {

    @Test
    void theOptionIsOnByDefaultAndTheTextOfTheClipboardIsEscaped() throws Exception {
        Setup setup = setup();
        String preview = body(send(setup, "preview"));
        assertThat(preview).contains("id=\"export-escape\"", "name=\"escapeShown\"").containsPattern("name=\"escape\" value=\"1\" checked");

        // a form that shows the option and has it unticked: no quote
        var off = send(setup, "export", "escapeShown", "1");
        assertThat(body(off)).contains("\t=HYPERLINK(1)\t").doesNotContain("&#39;=HYPERLINK").doesNotContain("id=\"paste-escaped\"");
    }

    @Test
    void anExportAsksedFromTheDefaultEscapesAndSaysSo() throws Exception {
        Setup setup = setup();
        String pasted = body(send(setup, "export"));
        assertThat(pasted).contains("&#39;=HYPERLINK(1)").contains("id=\"paste-escaped\"");
        // the end state: the listing was exported and the log says CLIPBOARD
        assertThat(jdbc.queryForObject("SELECT status FROM workspace_listing WHERE id = ?", String.class, setup.listing())).isEqualTo("EXPORTED");
        assertThat(jdbc.queryForObject("SELECT format FROM workspace_export_log WHERE account_id = ?", String.class, setup.account().id()))
                .isEqualTo("CLIPBOARD");
    }

    @Test
    void aTickedBoxEscapes() throws Exception {
        Setup setup = setup();
        assertThat(body(send(setup, "export", "escapeShown", "1", "escape", "1"))).contains("&#39;=HYPERLINK(1)");
    }
}
