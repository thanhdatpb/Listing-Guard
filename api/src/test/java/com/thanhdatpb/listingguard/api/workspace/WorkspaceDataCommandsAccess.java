package com.thanhdatpb.listingguard.api.workspace;

import java.util.List;

/** The package-private table list of the purge, for a test of another package. */
public final class WorkspaceDataCommandsAccess {

    private WorkspaceDataCommandsAccess() {
    }

    public static List<String> tables() {
        return WorkspaceDataCommands.TABLES;
    }
}
