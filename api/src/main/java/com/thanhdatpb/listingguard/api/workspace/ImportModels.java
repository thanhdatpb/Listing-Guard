package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The records of the import flow (ADR-059). */
public final class ImportModels {

    private ImportModels() {
    }

    /**
     * What a person chose on the steps of an import. {@code targets} gives each column of the file (zero-based) the standard field it feeds:
     * for an Amazon sheet a key of {@code standard-targets.yaml}, for an Etsy sheet a field of {@code etsy-fields.yaml}; an absent or empty
     * entry keeps the column as passthrough. {@code separators} cuts a list field's column at a separator key.
     */
    public record Params(UUID accountId, TemplateKind kind, UUID defaultOwner, ListingStatus newStatus, String sheet, int fieldRow,
                         int firstDataRow, Map<Integer, String> targets, Map<String, String> separators) {

        public Params {
            targets = Map.copyOf(targets);
            separators = Map.copyOf(separators);
        }
    }

    /** What happened to a row, or why it was not written. The codes are the tails of {@code workspace.issue.<code>} messages. */
    public record RowIssue(int line, String sku, String code) {
    }

    public enum TemplateAction {
        /** the file's header is the current version's */
        USE_EXISTING,
        /** the account has no template of this kind: the first import makes it from the file */
        CREATE_FIRST,
        /** the header differs from the current version: a new version */
        CREATE_NEW_VERSION,
        /** Etsy: the fixed fields */
        BUILTIN
    }

    public record Counts(int rows, int created, int updated, int unchanged, int errors) {
    }

    /** The result of reading a file against the workspace without writing: the four numbers of the preview and every problem. */
    public record Plan(Counts counts, List<RowIssue> issues, TemplateAction templateAction, String blocking, int ignoredCells,
                       int customizationsUnlinked, int customizationRows, List<String> headerAdded, List<String> headerRemoved) {

        public boolean canWrite() {
            return blocking == null && (counts.created() + counts.updated() > 0);
        }
    }

    /** What a commit did. */
    public record Result(Counts counts, int conflicts, boolean templateCreated, int templateVersion, int customizationsUnlinked) {
    }

    /** Template tracked with the Etsy or Amazon columns for the mapping step. */
    public record MappingColumn(int index, String header, String target, String origin) {
    }

    /** The first look at a template for the mapping step: the template it would use, the columns and each column's starting target. */
    public record MappingView(List<MappingColumn> columns, Template current) {
    }
}
