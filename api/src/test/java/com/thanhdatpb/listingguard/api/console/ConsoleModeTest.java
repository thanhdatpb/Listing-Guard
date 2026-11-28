package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

/** The reading of {@code listing-guard.console.mode}, {@code sheet-tools} and {@code import-roles} (ADR-064). */
class ConsoleModeTest {

    private static MockEnvironment env(String... pairs) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < pairs.length; i += 2) {
            environment.setProperty(pairs[i], pairs[i + 1]);
        }
        return environment;
    }

    @Test
    void theDefaultIsClassic() {
        ConsoleMode mode = new ConsoleMode(env());
        assertThat(mode.workspace()).isFalse();
        assertThat(mode.sheetTools()).isTrue();
        assertThat(mode.sheetToolsHidden()).isFalse();
        assertThat(mode.mayImport(ConsoleRole.MEMBER)).isTrue();
        assertThat(mode.mayImport(ConsoleRole.ADMIN)).isTrue();
    }

    @Test
    void classicIgnoresTheSheetToolsSettingAndKeepsTheToolsOn() {
        assertThat(new ConsoleMode(env("listing-guard.console.mode", "classic", "listing-guard.console.sheet-tools", "false")).sheetTools()).isTrue();
        // a workspace that is off keeps the console classic, whatever the mode says
        ConsoleMode withoutWorkspace = new ConsoleMode(env("listing-guard.console.mode", "workspace"));
        assertThat(withoutWorkspace.workspace()).isFalse();
        assertThat(withoutWorkspace.sheetTools()).isTrue();
        assertThat(withoutWorkspace.mayImport(ConsoleRole.MEMBER)).isTrue();
    }

    @Test
    void workspaceHidesTheSheetToolsAndLetsOnlyAnAdminImportUnlessToldOtherwise() {
        ConsoleMode mode = new ConsoleMode(env("listing-guard.console.mode", "Workspace", "listing-guard.workspace.enabled", "true"));
        assertThat(mode.workspace()).isTrue();
        assertThat(mode.sheetToolsHidden()).isTrue();
        assertThat(mode.mayImport(ConsoleRole.ADMIN)).isTrue();
        assertThat(mode.mayImport(ConsoleRole.MEMBER)).isFalse();

        ConsoleMode open = new ConsoleMode(env("listing-guard.console.mode", "workspace", "listing-guard.workspace.enabled", "true",
                "listing-guard.console.sheet-tools", "true", "listing-guard.workspace.import-roles", "admin, member"));
        assertThat(open.sheetToolsHidden()).isFalse();
        assertThat(open.mayImport(ConsoleRole.MEMBER)).isTrue();
    }

    @Test
    void anAdminImportsWhateverTheSettingSays() {
        ConsoleMode mode = new ConsoleMode(env("listing-guard.workspace.import-roles", "MEMBER"));
        assertThat(mode.mayImport(ConsoleRole.ADMIN)).isTrue();
        assertThat(mode.mayImport(ConsoleRole.MEMBER)).isTrue();
    }

    @Test
    void aSettingThatIsNotUnderstoodStopsTheStart() {
        assertThatThrownBy(() -> new ConsoleMode(env("listing-guard.console.mode", "sheets"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classic or workspace");
        assertThatThrownBy(() -> new ConsoleMode(env("listing-guard.workspace.import-roles", "ADMIN,BOSS")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BOSS");
        assertThatThrownBy(() -> new ConsoleMode(env("listing-guard.workspace.import-roles", " , ")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aWorkspaceModeWithoutTheWorkspaceWarnsOnceAtStart() {
        Logger logger = (Logger) LoggerFactory.getLogger(ConsoleMode.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new ConsoleMode(env("listing-guard.console.mode", "workspace"));
            new ConsoleMode(env("listing-guard.console.mode", "workspace", "listing-guard.workspace.enabled", "true"));
            new ConsoleMode(env("listing-guard.console.mode", "classic"));
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("listing-guard.workspace.enabled is false");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void theSectionOfAWorkspacePageIsTakenFromItsPath() {
        assertThat(ConsoleModeAdvice.WorkspaceNav.section("/console/workspace")).isEqualTo("listings");
        assertThat(ConsoleModeAdvice.WorkspaceNav.section("/console/workspace/listings/abc/edit")).isEqualTo("listings");
        assertThat(ConsoleModeAdvice.WorkspaceNav.section("/console/workspace/new")).isEqualTo("new");
        assertThat(ConsoleModeAdvice.WorkspaceNav.section("/console/workspace/clone/preview")).isEqualTo("new");
        assertThat(ConsoleModeAdvice.WorkspaceNav.section("/console/workspace/export")).isEqualTo("export");
        assertThat(ConsoleModeAdvice.WorkspaceNav.section(null)).isEqualTo("listings");
    }
}
