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

    public enum ChangeKind {
        IMPORT, EDIT, OWNER, DELETE
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
