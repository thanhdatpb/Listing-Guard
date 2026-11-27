package com.thanhdatpb.listingguard.api.workspace;

import java.util.Locale;
import java.util.Optional;

/** The enumerations of the workspace. Each equals a {@code CHECK} constraint of V57 (a test holds them). */
public final class WorkspaceTypes {

    private WorkspaceTypes() {
    }

    public enum Marketplace {
        AMAZON, ETSY
    }

    public enum ExportFormat {
        XLSM, TXT
    }

    /** What an export made, as the export log records it (ADR-063). Each equals a value of {@code ck_workspace_export_format} (V60). */
    public enum OutputFormat {
        XLSM, XLSX, TXT, CLIPBOARD, ETSY_POSTED
    }

    public enum TemplateKind {
        AMAZON_BULK(Marketplace.AMAZON), AMAZON_CUSTOM(Marketplace.AMAZON), ETSY_FIELDS(Marketplace.ETSY);

        private final Marketplace marketplace;

        TemplateKind(Marketplace marketplace) {
            this.marketplace = marketplace;
        }

        public Marketplace marketplace() {
            return marketplace;
        }
    }

    public enum ListingRole {
        PARENT, CHILD, STANDALONE
    }

    /** Draft, ready to export, exported, live. */
    public enum ListingStatus {
        DRAFT, READY_TO_EXPORT, EXPORTED, LIVE
    }

    public enum ListingSource {
        IMPORT, CLONE, NEW
    }

    /** The kinds of change a history row records. Each equals a value of {@code ck_workspace_history_kind} (V59); EXPORT is written by the export (ADR-063). */
    public enum ChangeKind {
        IMPORT, EDIT, OWNER, DELETE, NEW, CLONE, RESTORE, STATUS, EXPORT
    }

    public static <E extends Enum<E>> Optional<E> parse(Class<E> type, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, text.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
