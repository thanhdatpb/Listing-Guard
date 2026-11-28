package com.thanhdatpb.listingguard.api.console;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Which console the readers see (ADR-064): {@code classic} (the default: the menu, the home pages and the routes of every earlier version, byte
 * for byte) or {@code workspace} (the menu and the home pages start from the listing workspace, and the tools that work on a pasted Google
 * Sheet are hidden). {@code workspace} takes effect only while {@code listing-guard.workspace.enabled} is true: with the workspace off the
 * console stays classic and the start-up log says so once. The mode is read once at start-up; changing it needs a restart.
 *
 * <p>The mode changes what is shown and what a console path answers, nothing else: the API ({@code /api/v1}), the jobs, the MCP server and
 * the rules of the checks do not read it.
 */
@Component
public class ConsoleMode {

    private static final Logger log = LoggerFactory.getLogger(ConsoleMode.class);

    /** The value of {@code listing-guard.console.mode}. */
    public enum Mode {
        CLASSIC, WORKSPACE;

        static Mode parse(String text) {
            if (text == null || text.isBlank()) {
                return CLASSIC;
            }
            try {
                return valueOf(text.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("listing-guard.console.mode must be classic or workspace (it is '" + text + "').");
            }
        }
    }

    private final Mode requested;
    private final boolean workspace;
    private final boolean sheetTools;
    private final Set<ConsoleRole> importRoles;

    public ConsoleMode(Environment environment) {
        this.requested = Mode.parse(environment.getProperty("listing-guard.console.mode"));
        boolean workspaceOn = environment.getProperty("listing-guard.workspace.enabled", Boolean.class, false);
        this.workspace = requested == Mode.WORKSPACE && workspaceOn;
        if (requested == Mode.WORKSPACE && !workspaceOn) {
            // once: this is a singleton built at start-up
            log.warn("listing-guard.console.mode=workspace has no effect: listing-guard.workspace.enabled is false, so the console stays classic.");
        }
        this.sheetTools = !workspace || environment.getProperty("listing-guard.console.sheet-tools", Boolean.class, false);
        this.importRoles = importRoles(environment.getProperty("listing-guard.workspace.import-roles"), workspace);
    }

    /** Whether the workspace console is what readers see: the mode is {@code workspace} and the workspace is on. */
    public boolean workspace() {
        return workspace;
    }

    /** Whether the tools of a pasted Sheet (bulk check, edit, re-audit by file, the history of checks) are reachable. Always true in the classic console. */
    public boolean sheetTools() {
        return sheetTools;
    }

    /** Whether those tools are hidden: no menu entry, and their paths answer 404. */
    public boolean sheetToolsHidden() {
        return !sheetTools;
    }

    /** Whether a person of this role may import data into the workspace (ADR-064 §2). An ADMIN always may: the setting lets MEMBERs in. */
    public boolean mayImport(ConsoleRole role) {
        return role == ConsoleRole.ADMIN || importRoles.contains(role);
    }

    /** The roles that may import: what {@code import-roles} says, else ADMIN in the workspace console and both roles in the classic one (ADR-059). */
    static Set<ConsoleRole> importRoles(String text, boolean workspace) {
        if (text == null || text.isBlank()) {
            return workspace ? EnumSet.of(ConsoleRole.ADMIN) : EnumSet.allOf(ConsoleRole.class);
        }
        Set<ConsoleRole> roles = EnumSet.noneOf(ConsoleRole.class);
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                roles.add(ConsoleRole.parse(part).orElseThrow(() -> new IllegalStateException(
                        "listing-guard.workspace.import-roles lists ADMIN, MEMBER or both, separated by a comma (it has '" + part.strip() + "').")));
            }
        }
        if (roles.isEmpty()) {
            throw new IllegalStateException("listing-guard.workspace.import-roles lists ADMIN, MEMBER or both.");
        }
        return roles;
    }
}
