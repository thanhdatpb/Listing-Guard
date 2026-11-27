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

    public ConsoleWorkspaceAdvice(@Value("${listing-guard.workspace.enabled:false}") boolean workspaceEnabled) {
        this.workspaceEnabled = workspaceEnabled;
    }

    @ModelAttribute("workspaceEnabled")
    public boolean workspaceEnabled() {
        return workspaceEnabled;
    }
}
