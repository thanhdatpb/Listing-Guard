package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ChangeKind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                         int firstDataRow, Map<Integer, String> targets, Map<String, String> separators, Set<Integer> overwriteLines,
                         Set<Integer> overwriteGroups) {

        public Params {
            targets = Map.copyOf(targets);
            separators = Map.copyOf(separators);
            overwriteLines = Set.copyOf(overwriteLines);
            overwriteGroups = Set.copyOf(overwriteGroups);
        }

        /** An import that overwrites no row that was changed in the workspace (ADR-062 §8): the import of before the editor. */
        public Params(UUID accountId, TemplateKind kind, UUID defaultOwner, ListingStatus newStatus, String sheet, int fieldRow,
                      int firstDataRow, Map<Integer, String> targets, Map<String, String> separators) {
            this(accountId, kind, defaultOwner, newStatus, sheet, fieldRow, firstDataRow, targets, separators, Set.of(), Set.of());
        }
    }

    /**
     * A row of the file whose listing was edited, created, cloned or restored in the workspace after its latest import (ADR-062 §8): the
     * preview says "changed in the workspace" and the row is not written unless the person chooses to overwrite it.
     */
    public record EditedRow(int line, String sku, String parentSku, ChangeKind kind, Instant at, boolean overwrite) {
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
                       int customizationsUnlinked, int customizationRows, List<String> headerAdded, List<String> headerRemoved,
                       List<EditedRow> edited) {

        public Plan {
            edited = List.copyOf(edited);
        }

        /** A plan with no row changed in the workspace: the plan of before the editor. */
        public Plan(Counts counts, List<RowIssue> issues, TemplateAction templateAction, String blocking, int ignoredCells,
                    int customizationsUnlinked, int customizationRows, List<String> headerAdded, List<String> headerRemoved) {
            this(counts, issues, templateAction, blocking, ignoredCells, customizationsUnlinked, customizationRows, headerAdded, headerRemoved,
                    List.of());
        }

        /** There is something to write, or a row to decide on (the rows changed in the workspace are written only if chosen). */
        public boolean canWrite() {
            return blocking == null && (counts.created() + counts.updated() + edited.size() > 0);
        }
    }

    /** What a commit did. */
    public record Result(Counts counts, int conflicts, boolean templateCreated, int templateVersion, int customizationsUnlinked,
                         int overwritten, int keptEdited) {

        public Result(Counts counts, int conflicts, boolean templateCreated, int templateVersion, int customizationsUnlinked) {
            this(counts, conflicts, templateCreated, templateVersion, customizationsUnlinked, 0, 0);
        }
    }

    /** Template tracked with the Etsy or Amazon columns for the mapping step. */
    public record MappingColumn(int index, String header, String target, String origin) {
    }

    /** The first look at a template for the mapping step: the template it would use, the columns and each column's starting target. */
    public record MappingView(List<MappingColumn> columns, Template current) {
    }
}
