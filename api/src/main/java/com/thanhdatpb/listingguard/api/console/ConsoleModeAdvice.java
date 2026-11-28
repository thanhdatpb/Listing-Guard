package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.console.workspace.AuditHomeCard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tells every console page which console it is in (ADR-064): {@code workspaceMode} and {@code sheetToolsHidden} decide the menu and which
 * links a page may show, {@code workspaceNav} holds the two values of the menu that depend on who reads it. All three are only read by the
 * templates; in the classic console they are false and the pages render as before.
 */
@ControllerAdvice(basePackages = "com.thanhdatpb.listingguard.api.console")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleModeAdvice {

    /**
     * The reader-dependent part of the workspace menu, read lazily: a page that does not print an entry does not pay for it, and a page of the
     * classic console never builds one.
     */
    public static final class WorkspaceNav {
        private final ConsoleMode mode;
        private final ConsoleAccountRoles roles;
        private final ObjectProvider<AuditHomeCard> card;
        private final ConsoleUser user;
        private final String section;
        private Optional<Long> newRisk;
        private Boolean mayImport;

        WorkspaceNav(ConsoleMode mode, ConsoleAccountRoles roles, ObjectProvider<AuditHomeCard> card, ConsoleUser user,
                     String path) {
            this.section = section(path);
            this.mode = mode;
            this.roles = roles;
            this.card = card;
            this.user = user;
        }

        /**
         * Which entry of the "Listing workspace" group a page of {@code /console/workspace} belongs to: {@code new} (the new listing and the
         * clone), {@code export} (the export and its confirmation) or {@code listings} (the list, a listing and its editor, history and status).
         * Those pages all name the same menu place; the path tells them apart.
         */
        public String section() {
            return section;
        }

        static String section(String path) {
            String p = path == null ? "" : path;
            if (p.equals("/console/workspace/new") || p.startsWith("/console/workspace/new/") || p.startsWith("/console/workspace/clone")) {
                return "new";
            }
            if (p.equals("/console/workspace/export") || p.startsWith("/console/workspace/export/")) {
                return "export";
            }
            return "listings";
        }

        /** The listings with a new risk that nobody marked as seen, for the reader (an ADMIN: the whole client; a MEMBER: their own); null while the background audit is off. */
        public Long newRisk() {
            if (newRisk == null) {
                AuditHomeCard audit = card.getIfAvailable();
                newRisk = user == null || audit == null ? Optional.empty()
                        : audit.of(user).map(AuditHomeCard.Card::count);
            }
            return newRisk.orElse(null);
        }

        /** Whether the reader's role may import data ({@code listing-guard.workspace.import-roles}). */
        public boolean mayImport() {
            if (mayImport == null) {
                mayImport = user != null && roles.account(user).map(account -> mode.mayImport(account.getRole())).orElse(false);
            }
            return mayImport;
        }
    }

    private final ConsoleMode mode;
    private final ConsoleAccountRoles roles;
    private final ObjectProvider<AuditHomeCard> card;

    public ConsoleModeAdvice(ConsoleMode mode, ConsoleAccountRoles roles, ObjectProvider<AuditHomeCard> card) {
        this.mode = mode;
        this.roles = roles;
        this.card = card;
    }

    @ModelAttribute("workspaceMode")
    public boolean workspaceMode() {
        return mode.workspace();
    }

    @ModelAttribute("sheetToolsHidden")
    public boolean sheetToolsHidden() {
        return mode.sheetToolsHidden();
    }

    @ModelAttribute("workspaceNav")
    public WorkspaceNav workspaceNav(HttpServletRequest request) {
        if (!mode.workspace()) {
            return null;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        ConsoleUser user = authentication != null && authentication.getPrincipal() instanceof ConsoleUser found ? found : null;
        return new WorkspaceNav(mode, roles, card, user, request.getRequestURI());
    }
}
