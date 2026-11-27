package com.thanhdatpb.listingguard.api.console.workspace;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetRejected;
import java.util.Locale;

/** The words of the workspace pages, in the language of the request (ADR-028): each is a key of both message bundles. */
final class WorkspaceLabels {

    private final ConsoleMessages messages;

    WorkspaceLabels(ConsoleMessages messages) {
        this.messages = messages;
    }

    String status(ListingStatus status) {
        return messages.get("workspace.status." + lower(status));
    }

    String role(ListingRole role) {
        return messages.get("workspace.role." + lower(role));
    }

    String marketplace(Marketplace marketplace) {
        return messages.get("workspace.marketplace." + lower(marketplace));
    }

    String kind(TemplateKind kind) {
        return messages.get("workspace.kind." + lower(kind));
    }

    /** A problem of one row of an import. */
    String issue(String code) {
        return messages.get("workspace.issue." + code);
    }

    /** A refusal of the workspace: {@code WorkspaceException#code}. */
    String error(String code) {
        return messages.get("workspace.error." + code);
    }

    /** Why a file was not read: the reason of a {@link SheetRejected}. */
    String sheetProblem(SheetRejected.Reason reason) {
        return messages.get("workspace.sheet." + lower(reason));
    }

    /** Where a column's starting target came from: remembered, guessed, none. */
    String origin(String origin) {
        return messages.get("workspace.origin." + origin);
    }

    String action(String action) {
        return messages.get("workspace.template.action." + action.toLowerCase(Locale.ROOT));
    }

    String target(String key) {
        return key == null || key.isEmpty() ? messages.get("workspace.target.skip") : messages.get("workspace.target." + key);
    }

    /** The word for an export format: XLSM or TXT. */
    String format(String code) {
        return messages.get("workspace.format." + code.toLowerCase(Locale.ROOT));
    }

    String separator(String key) {
        return messages.get("workspace.separator." + key);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
