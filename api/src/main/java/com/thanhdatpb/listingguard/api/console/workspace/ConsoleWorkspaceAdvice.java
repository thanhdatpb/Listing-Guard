package com.thanhdatpb.listingguard.api.console.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tells every console page whether the listing workspace is on (ADR-059), so the sidebar shows the "Kho listing" group only then. The
 * paths themselves do not depend on this: with the flag off no controller of the workspace exists and every path is a 404.
 */
@ControllerAdvice(basePackages = "com.thanhdatpb.listingguard.api.console")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleWorkspaceAdvice {

    private final boolean workspaceEnabled;
    private final boolean auditEnabled;

    public ConsoleWorkspaceAdvice(@Value("${listing-guard.workspace.enabled:false}") boolean workspaceEnabled,
                                  @Value("${listing-guard.workspace.audit.enabled:false}") boolean auditEnabled) {
        this.workspaceEnabled = workspaceEnabled;
        this.auditEnabled = auditEnabled;
    }

    /** Whether the workspace's background audit is on (ADR-061): the sidebar shows its page only then. */
    @ModelAttribute("workspaceAuditEnabled")
    public boolean workspaceAuditEnabled() {
        return workspaceEnabled && auditEnabled;
    }

    @ModelAttribute("workspaceEnabled")
    public boolean workspaceEnabled() {
        return workspaceEnabled;
    }
}
