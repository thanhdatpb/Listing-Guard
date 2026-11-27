package com.thanhdatpb.listingguard.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Counts;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.EditedRow;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.MappingColumn;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.MappingView;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Plan;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Result;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.RowIssue;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.TemplateAction;
import com.thanhdatpb.listingguard.api.workspace.StandardFields.Built;
import com.thanhdatpb.listingguard.api.workspace.StandardFields.ColumnUse;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets.Target;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.SourceFile;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.CustomizationState;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.CustomizationWrite;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.Existing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.Write;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Mapping;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.TemplateColumn;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ChangeKind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingSource;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a sheet against the workspace and writes it (ADR-059). {@link #plan} changes nothing and answers the four numbers of the preview
 * (new, updated, unchanged, error) with every problem; {@link #commit} runs the same reading again inside a transaction that holds the
 * seller account's lock, so what is written is what the preview described for that moment. A listing is identified by
 * {@code (seller account, sku)}: running the same file again changes nothing. A row with a problem is never written and never guessed at: a
 * missing SKU, a SKU that appears twice in the file (every occurrence), a parent that does not exist, an unknown responsible person, a
 * listing that a MEMBER may not change. The log carries ids and numbers, never a SKU, a title or any other cell.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class ListingImportService {

    private static final Logger log = LoggerFactory.getLogger(ListingImportService.class);
    static final int SKU_MAX = 255;
    private static final int ISSUES_KEPT = 1000;
    private static final int HEADER_DIFF_SHOWN = 20;

    private final WorkspaceAccounts accounts;
    private final WorkspaceTemplates templates;
    private final WorkspaceListings listings;
    private final TemplateService templateService;
    private final ImportStages stages;
    private final ConsoleAccountRoles roles;
    private final WorkspaceAccess access;
    private final StandardTargets targets;
    private final EtsyFields etsy;
    private final StandardFields standardFields;

    public ListingImportService(WorkspaceAccounts accounts, WorkspaceTemplates templates, WorkspaceListings listings,
                                TemplateService templateService, ImportStages stages, ConsoleAccountRoles roles, WorkspaceAccess access,
                                StandardTargets targets, EtsyFields etsy, ObjectMapper mapper) {
        this.accounts = accounts;
        this.templates = templates;
        this.listings = listings;
        this.templateService = templateService;
        this.stages = stages;
        this.roles = roles;
        this.access = access;
        this.targets = targets;
        this.etsy = etsy;
        this.standardFields = new StandardFields(targets, mapper);
    }

    // ------------------------------------------------------------------ the mapping step

    /**
     * The columns of the sheet with the target each starts from: what the template remembers, else the guess, else none. Nothing is run:
     * a guess is a default the person confirms.
     */
    public MappingView mappingView(Actor actor, UUID accountId, TemplateKind kind, SheetGrid grid, int fieldRow) {
        SellerAccount account = accounts.find(actor.clientId(), accountId).orElseThrow(() -> new WorkspaceException("account.unknown"));
        List<String> cells = grid.row(fieldRow);
        int width = TemplateService.trimmedWidth(cells);
        List<TemplateColumn> columns = WorkspaceTemplates.columnsOf(cells, width);
        Template current = templates.current(actor.clientId(), accountId, kind).orElse(null);
        List<String> allowed = templateService.allowedTargets(kind);
        List<MappingColumn> view = new ArrayList<>();
        Set<String> claimed = new HashSet<>();
        for (TemplateColumn column : columns) {
            String target = null;
            String origin = "none";
            if (current != null) {
                if (kind == TemplateKind.ETSY_FIELDS) {
                    String remembered = current.mapping().source().get(column.header().strip());
                    if (remembered != null && allowed.contains(remembered)) {
                        target = remembered;
                        origin = "remembered";
                    }
                } else if (current.columns().stream().anyMatch(c -> c.key().equals(column.key()))) {
                    // a column the template already has: its mapping is what a person confirmed, and "keep as it is" is part of it
                    String remembered = current.mapping().targets().get(column.key());
                    if (remembered != null && allowed.contains(remembered)) {
                        target = remembered;
                        origin = "remembered";
                    }
                    view.add(finish(targets, account, claimed, column, target, origin));
                    continue;
                }
            }
            if (target == null) {
                Target guess = targets.guess(account.marketplace(), column.header()).orElse(null);
                if (guess != null && allowed.contains(guess.key())) {
                    target = guess.key();
                    origin = "guessed";
                }
            }
            view.add(finish(targets, account, claimed, column, target, origin));
        }
        return new MappingView(view, current);
    }

    /** A target that takes one column goes to the first column that claims it. */
    private static MappingColumn finish(StandardTargets targets, SellerAccount account, Set<String> claimed, TemplateColumn column, String target,
                                        String origin) {
        if (target != null) {
            boolean multiple = targets.find(account.marketplace(), target).map(Target::multiple).orElse(false);
            if (!multiple && !claimed.add(target)) {
                target = null;
                origin = "none";
            }
        }
        return new MappingColumn(column.index(), column.header(), target == null ? "" : target, origin);
    }

    // ------------------------------------------------------------------ plan

    public Plan plan(Actor actor, Params params, SheetGrid grid) {
        return prepare(actor, params, grid).plan;
    }

    /** One data row that can be written, before the template of the write is known. */
    record Draft(int line, String sku, String parentSku, ListingRole role, String title, Map<String, String> data,
                         ObjectNode fields, UUID owner, boolean ownerExplicit, Existing existing, boolean unchanged, boolean edited,
                         boolean overwrite) {

        /** A row nobody changed in the workspace. */
        Draft(int line, String sku, String parentSku, ListingRole role, String title, Map<String, String> data, ObjectNode fields,
              UUID owner, boolean ownerExplicit, Existing existing, boolean unchanged) {
            this(line, sku, parentSku, role, title, data, fields, owner, ownerExplicit, existing, unchanged, false, false);
        }

        /** Whether the commit writes this row: every row but one changed in the workspace that the person did not choose to overwrite. */
        boolean written() {
            return !unchanged && (!edited || overwrite);
        }
    }

    static final class Prepared {
        Plan plan;
        SellerAccount account;
        Template current;
        TemplateAction action;
        List<TemplateColumn> columns = List.of();
        Mapping mapping = Mapping.empty();
        List<Draft> drafts = List.of();
        Map<String, Map<String, String>> customRows = new LinkedHashMap<>();
        Map<String, CustomizationState> customCurrent = Map.of();
        boolean mappingChanged;
    }

    Prepared prepare(Actor actor, Params p, SheetGrid grid) {
        Prepared prep = new Prepared();
        prep.account = accounts.find(actor.clientId(), p.accountId()).orElseThrow(() -> new WorkspaceException("account.unknown"));
        if (!prep.account.enabled()) {
            throw new WorkspaceException("account.disabled");
        }
        if (p.kind().marketplace() != prep.account.marketplace()) {
            throw new WorkspaceException("kind.marketplace");
        }
        Map<UUID, String> emails = roles.emails(actor.clientId());
        if (p.defaultOwner() != null && !emails.containsKey(p.defaultOwner())) {
            throw new WorkspaceException("owner.unknown");
        }
        if (p.firstDataRow() <= p.fieldRow() || p.fieldRow() < 1) {
            throw new WorkspaceException("rows.order");
        }
        List<String> fieldCells = grid.row(p.fieldRow());
        int width = TemplateService.trimmedWidth(fieldCells);
        if (width == 0) {
            throw new WorkspaceException("header.empty");
        }
        List<TemplateColumn> fileColumns = WorkspaceTemplates.columnsOf(fieldCells, width);
        String blocking = null;
        List<String> added = List.of();
        List<String> removed = List.of();

        // the template this import would use
        Template current = templates.current(actor.clientId(), p.accountId(), p.kind()).orElse(null);
        TemplateAction action;
        if (p.kind() == TemplateKind.ETSY_FIELDS) {
            action = TemplateAction.BUILTIN;
            prep.columns = current != null ? current.columns() : etsyColumns();
        } else {
            String hash = WorkspaceTemplates.headerHash(fileColumns);
            if (current == null) {
                action = TemplateAction.CREATE_FIRST;
            } else if (current.headerHash().equals(hash)) {
                action = TemplateAction.USE_EXISTING;
            } else {
                action = TemplateAction.CREATE_NEW_VERSION;
                Set<String> before = new HashSet<>(current.columns().stream().map(TemplateColumn::header).toList());
                Set<String> after = new HashSet<>(fileColumns.stream().map(TemplateColumn::header).toList());
                added = fileColumns.stream().map(TemplateColumn::header).filter(h -> !before.contains(h)).limit(HEADER_DIFF_SHOWN).toList();
                removed = current.columns().stream().map(TemplateColumn::header).filter(h -> !after.contains(h)).limit(HEADER_DIFF_SHOWN).toList();
            }
            if (action != TemplateAction.USE_EXISTING && !actor.admin()) {
                blocking = "template.admin_required";
            }
            prep.columns = action == TemplateAction.USE_EXISTING ? current.columns() : fileColumns;
        }
        prep.current = current;
        prep.action = action;

        // what each column of the file is
        List<ColumnUse> uses = new ArrayList<>();
        Map<String, String> separators = new LinkedHashMap<>();
        boolean useChosen = actor.admin() || p.kind() == TemplateKind.ETSY_FIELDS || current == null;
        if (p.kind() == TemplateKind.ETSY_FIELDS) {
            Map<String, Integer> seen = new HashMap<>();
            Map<String, String> sourceMap = new LinkedHashMap<>();
            List<String> allowed = templateService.allowedTargets(p.kind());
            for (TemplateColumn column : fileColumns) {
                String target = p.targets().get(column.index());
                if (target == null || target.isBlank()) {
                    uses.add(new ColumnUse(uniqueKey(seen, "extra:" + (column.header().strip().isEmpty() ? column.key() : column.header().strip())),
                            column.header(), null));
                    continue;
                }
                if (!allowed.contains(target)) {
                    throw new WorkspaceException("mapping.target.unknown");
                }
                uses.add(new ColumnUse(uniqueKey(seen, target), column.header(), target));
                if (!column.header().strip().isEmpty()) {
                    sourceMap.put(column.header().strip(), target);
                }
            }
            prep.mapping = new Mapping(current != null ? current.mapping().targets() : identity(), p.separators(), sourceMap);
            separators.putAll(p.separators());
        } else {
            Map<Integer, String> chosen = new LinkedHashMap<>();
            if (useChosen) {
                chosen.putAll(p.targets());
                prep.mapping = templateService.mapping(p.kind(), prep.columns, chosen, p.separators());
                prep.mappingChanged = current == null || !prep.mapping.equals(current.mapping());
            } else {
                prep.mapping = current.mapping();
            }
            separators.putAll(prep.mapping.separators());
            for (TemplateColumn column : prep.columns) {
                uses.add(new ColumnUse(column.key(), column.header(), prep.mapping.targets().get(column.key())));
            }
        }
        int skuColumns = 0;
        for (ColumnUse use : uses) {
            if ("sku".equals(use.target())) {
                skuColumns++;
            }
        }
        if (skuColumns != 1) {
            throw new WorkspaceException(skuColumns == 0 ? "mapping.sku.required" : "mapping.target.duplicate");
        }

        if (p.kind() == TemplateKind.AMAZON_CUSTOM) {
            readCustomRows(actor, p, grid, prep, uses, width, blocking, added, removed);
            return prep;
        }
        readListingRows(actor, p, grid, prep, uses, width, separators, emails, blocking, added, removed);
        return prep;
    }

    private List<TemplateColumn> etsyColumns() {
        List<TemplateColumn> columns = new ArrayList<>();
        for (int i = 0; i < etsy.fields().size(); i++) {
            columns.add(new TemplateColumn(i, etsy.fields().get(i), etsy.fields().get(i)));
        }
        return columns;
    }

    private Map<String, String> identity() {
        Map<String, String> identity = new LinkedHashMap<>();
        etsy.fields().forEach(f -> identity.put(f, f));
        return identity;
    }

    private static String uniqueKey(Map<String, Integer> seen, String base) {
        int n = seen.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + "~" + n;
    }

    private static boolean blank(List<String> cells) {
        for (String cell : cells) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String cell(List<String> cells, int index) {
        return index < cells.size() && cells.get(index) != null ? cells.get(index) : "";
    }

    private void readListingRows(Actor actor, Params p, SheetGrid grid, Prepared prep, List<ColumnUse> uses, int width,
                                 Map<String, String> separators, Map<UUID, String> emails, String blocking, List<String> added,
                                 List<String> removed) {
        Marketplace marketplace = prep.account.marketplace();
        Map<String, UUID> byEmail = new HashMap<>();
        emails.forEach((id, email) -> byEmail.put(email.toLowerCase(Locale.ROOT), id));
        record Row(int line, Map<String, String> data, Built built) {
        }
        List<Row> rows = new ArrayList<>();
        int ignored = 0;
        for (int r = p.firstDataRow(); r <= grid.rowCount(); r++) {
            List<String> cells = grid.row(r);
            if (blank(cells)) {
                continue;
            }
            Map<String, String> data = new LinkedHashMap<>();
            for (int i = 0; i < uses.size(); i++) {
                String value = cell(cells, i);
                if (!value.isBlank()) {
                    data.put(uses.get(i).dataKey(), value);
                }
            }
            for (int i = width; i < cells.size(); i++) {
                if (cells.get(i) != null && !cells.get(i).isBlank()) {
                    ignored++;
                }
            }
            rows.add(new Row(r, data, standardFields.build(marketplace, uses, data, separators)));
        }

        Map<String, Integer> occurrences = new HashMap<>();
        for (Row row : rows) {
            if (!row.built().sku().isEmpty()) {
                occurrences.merge(row.built().sku(), 1, Integer::sum);
            }
        }
        Set<String> fileSkus = occurrences.keySet();
        Set<String> parentRefs = new HashSet<>();
        rows.forEach(row -> {
            if (!row.built().parentSku().isEmpty()) {
                parentRefs.add(row.built().parentSku());
            }
        });
        Map<String, Existing> existing = listings.existing(actor.clientId(), p.accountId(), fileSkus);
        Set<String> parentsKnown = new HashSet<>(fileSkus);
        Set<String> missingFromFile = new HashSet<>(parentRefs);
        missingFromFile.removeAll(fileSkus);
        parentsKnown.addAll(listings.existingSkus(actor.clientId(), p.accountId(), missingFromFile));
        Set<String> referencedByExisting = listings.parentsReferenced(actor.clientId(), p.accountId(), fileSkus);
        // listings that were edited, created, cloned or restored in the workspace after their latest import (ADR-062 §8)
        Map<UUID, WorkspaceListings.Edited> editedInWorkspace = listings.editedSinceImport(actor.clientId(),
                existing.values().stream().map(Existing::id).toList());
        Map<Integer, String> skuOfLine = new HashMap<>();
        rows.forEach(row -> skuOfLine.put(row.line(), row.built().sku()));
        Set<String> overwriteFamilies = new HashSet<>();
        p.overwriteGroups().forEach(line -> {
            String sku = skuOfLine.get(line);
            if (sku != null) {
                overwriteFamilies.add(sku);
            }
        });
        List<EditedRow> edited = new ArrayList<>();

        List<RowIssue> issues = new ArrayList<>();
        List<Draft> drafts = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int errors = 0;
        UUID templateId = prep.action == TemplateAction.USE_EXISTING || prep.action == TemplateAction.BUILTIN && prep.current != null
                ? prep.current.id() : null;
        for (Row row : rows) {
            Built built = row.built();
            String code = null;
            if (built.sku().isEmpty()) {
                code = "missing_sku";
            } else if (built.sku().codePointCount(0, built.sku().length()) > SKU_MAX) {
                code = "sku_too_long";
            } else if (occurrences.get(built.sku()) > 1) {
                code = "duplicate_sku";
            } else if (!built.parentSku().isEmpty() && built.parentSku().equals(built.sku())) {
                code = "parent_self";
            } else if (!built.parentSku().isEmpty() && !parentsKnown.contains(built.parentSku())) {
                code = "parent_missing";
            } else if (built.explicitRole() == ListingRole.CHILD && built.parentSku().isEmpty()) {
                code = "child_without_parent";
            }
            Existing old = code == null ? existing.get(built.sku()) : null;
            UUID owner = null;
            boolean explicit = false;
            if (code == null) {
                if (!actor.admin()) {
                    owner = old != null ? old.ownerAccountId() : actor.accountId();
                    if (old != null && !access.canEdit(actor, old.ownerAccountId())) {
                        code = "not_owner";
                    }
                } else if (!built.ownerEmail().isEmpty()) {
                    owner = byEmail.get(built.ownerEmail());
                    explicit = true;
                    if (owner == null) {
                        code = "owner_unknown";
                    }
                } else {
                    owner = old != null ? old.ownerAccountId() : p.defaultOwner();
                }
            }
            if (code != null) {
                errors++;
                if (issues.size() < ISSUES_KEPT) {
                    issues.add(new RowIssue(row.line(), built.sku(), code));
                }
                continue;
            }
            ListingRole role = built.explicitRole() != null ? built.explicitRole()
                    : !built.parentSku().isEmpty() ? ListingRole.CHILD
                    : parentRefs.contains(built.sku()) || referencedByExisting.contains(built.sku()) ? ListingRole.PARENT
                    : ListingRole.STANDALONE;
            String hash = hash(row.data(), role);
            boolean same = old != null && old.contentHash().equals(hash) && templateId != null && templateId.equals(old.templateId())
                    && (!explicit || java.util.Objects.equals(owner, old.ownerAccountId()));
            WorkspaceListings.Edited inWorkspace = old != null && !same ? editedInWorkspace.get(old.id()) : null;
            boolean overwrite = inWorkspace != null && (p.overwriteLines().contains(row.line()) || overwriteFamilies.contains(built.sku())
                    || !built.parentSku().isEmpty() && overwriteFamilies.contains(built.parentSku()));
            if (old == null) {
                created++;
            } else if (same) {
                unchanged++;
            } else if (inWorkspace != null) {
                // not counted as an update: it is written only if the person chooses to overwrite it
                edited.add(new EditedRow(row.line(), built.sku(), built.parentSku().isEmpty() ? null : built.parentSku(), inWorkspace.kind(),
                        inWorkspace.at(), overwrite));
            } else {
                updated++;
            }
            drafts.add(new Draft(row.line(), built.sku(), built.parentSku().isEmpty() ? null : built.parentSku(), role, built.title(),
                    row.data(), built.fields(), owner, explicit, old, same, inWorkspace != null, overwrite));
        }
        prep.drafts = drafts;
        prep.plan = new Plan(new Counts(rows.size(), created, updated, unchanged, errors), issues, prep.action, blocking, ignored, 0, 0,
                added, removed, edited);
    }

    /** The content hash of a row: {@link ListingContent#hash}, shared with the editor and the clone (ADR-062 §1). */
    static String hash(Map<String, String> data, ListingRole role) {
        return ListingContent.hash(data, role);
    }

    private void readCustomRows(Actor actor, Params p, SheetGrid grid, Prepared prep, List<ColumnUse> uses, int width, String blocking,
                                List<String> added, List<String> removed) {
        List<RowIssue> issues = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        record Row(int line, String sku, Map<String, String> data) {
        }
        List<Row> rows = new ArrayList<>();
        int ignored = 0;
        for (int r = p.firstDataRow(); r <= grid.rowCount(); r++) {
            List<String> cells = grid.row(r);
            if (blank(cells)) {
                continue;
            }
            Map<String, String> data = new LinkedHashMap<>();
            String sku = "";
            for (int i = 0; i < uses.size(); i++) {
                String value = cell(cells, i);
                if (!value.isBlank()) {
                    data.put(uses.get(i).dataKey(), value);
                    if ("sku".equals(uses.get(i).target())) {
                        sku = value.strip();
                    }
                }
            }
            for (int i = width; i < cells.size(); i++) {
                if (cells.get(i) != null && !cells.get(i).isBlank()) {
                    ignored++;
                }
            }
            rows.add(new Row(r, sku, data));
            if (!sku.isEmpty()) {
                occurrences.merge(sku, 1, Integer::sum);
            }
        }
        Map<String, CustomizationState> current = listings.customizations(actor.clientId(), p.accountId(), occurrences.keySet());
        Set<String> linked = listings.existingSkus(actor.clientId(), p.accountId(), occurrences.keySet());
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int errors = 0;
        int unlinked = 0;
        for (Row row : rows) {
            String code = row.sku().isEmpty() ? "missing_sku"
                    : row.sku().codePointCount(0, row.sku().length()) > SKU_MAX ? "sku_too_long"
                    : occurrences.get(row.sku()) > 1 ? "duplicate_sku" : null;
            if (code != null) {
                errors++;
                if (issues.size() < ISSUES_KEPT) {
                    issues.add(new RowIssue(row.line(), row.sku(), code));
                }
                continue;
            }
            CustomizationState old = current.get(row.sku());
            String hash = WorkspaceJson.contentHash(new TreeMap<>(row.data()), "CUSTOM");
            if (old == null) {
                created++;
            } else if (old.contentHash().equals(hash) && prep.current != null && prep.action == TemplateAction.USE_EXISTING) {
                unchanged++;
            } else {
                updated++;
            }
            if (!linked.contains(row.sku())) {
                unlinked++;
                if (issues.size() < ISSUES_KEPT) {
                    issues.add(new RowIssue(row.line(), row.sku(), "customization_unlinked"));
                }
            }
            prep.customRows.put(row.sku(), row.data());
        }
        prep.customCurrent = current;
        prep.plan = new Plan(new Counts(rows.size(), created, updated, unchanged, errors), issues, prep.action, blocking, ignored, unlinked,
                rows.size(), added, removed);
    }

    // ------------------------------------------------------------------ commit

    /**
     * Writes what {@link #plan} described. Rows with a problem are skipped (they are in the plan's issues); a listing that someone else changed
     * between the read and the write is counted in {@code conflicts} and left as it is. The file is deleted from the stage afterwards.
     */
    @Transactional
    public Result commit(Actor actor, Params p, SheetGrid grid, SourceFile file, UUID stageId) {
        if (access.actor(new com.thanhdatpb.listingguard.api.console.ConsoleUser(actor.email(), actor.clientId())).isEmpty()) {
            throw new WorkspaceException("forbidden");
        }
        listings.lockAccount(p.accountId());
        Prepared prep = prepare(actor, p, grid);
        if (prep.plan.blocking() != null) {
            throw new WorkspaceException(prep.plan.blocking());
        }
        int overwritten = (int) prep.drafts.stream().filter(d -> d.edited() && d.overwrite() && !d.unchanged()).count();
        int keptEdited = prep.plan.edited().size() - overwritten;
        if (prep.plan.counts().created() + prep.plan.counts().updated() + overwritten == 0 && prep.action == TemplateAction.USE_EXISTING
                || prep.plan.counts().created() + prep.plan.counts().updated() + overwritten == 0 && prep.action == TemplateAction.BUILTIN) {
            if (stageId != null) {
                stages.delete(actor.clientId(), stageId);
            }
            return new Result(prep.plan.counts(), 0, false, prep.current == null ? 0 : prep.current.version(), prep.plan.customizationsUnlinked(),
                    0, keptEdited);
        }
        Template template;
        boolean created = false;
        switch (prep.action) {
            case BUILTIN -> template = prep.current != null ? prep.current : templateService.ensureEtsy(actor.clientId(), p.accountId());
            case USE_EXISTING -> template = prep.current;
            default -> {
                List<TemplateColumn> columns = prep.columns;
                template = templateService.insertVersion(actor.clientId(), p.accountId(), p.kind(), grid, columns,
                        WorkspaceTemplates.headerHash(columns), p.fieldRow(), p.firstDataRow(), file, prep.mapping);
                created = true;
            }
        }
        if (!created && prep.mappingChanged && actor.admin() && p.kind() != TemplateKind.ETSY_FIELDS) {
            templates.updateMapping(actor.clientId(), template.id(), prep.mapping);
        }
        if (p.kind() == TemplateKind.ETSY_FIELDS && !prep.mapping.source().isEmpty()
                && !prep.mapping.source().equals(template.mapping().source())) {
            templates.updateMapping(actor.clientId(), template.id(),
                    new Mapping(template.mapping().targets(), prep.mapping.separators(), prep.mapping.source()));
        }
        int conflicts = 0;
        if (p.kind() == TemplateKind.AMAZON_CUSTOM) {
            writeCustom(actor, p, prep, template);
        } else {
            conflicts = writeListings(actor, p, prep, template);
        }
        if (stageId != null) {
            stages.delete(actor.clientId(), stageId);
        }
        log.info("Workspace import into account {} of client {}: {} rows, {} new, {} updated, {} unchanged, {} errors, {} conflicts",
                p.accountId(), actor.clientId(), prep.plan.counts().rows(), prep.plan.counts().created(), prep.plan.counts().updated(),
                prep.plan.counts().unchanged(), prep.plan.counts().errors(), conflicts);
        Counts counts = prep.plan.counts();
        return new Result(new Counts(counts.rows(), counts.created(), counts.updated() + overwritten, counts.unchanged(), counts.errors()),
                conflicts, created, template.version(), prep.plan.customizationsUnlinked(), overwritten, keptEdited);
    }

    private int writeListings(Actor actor, Params p, Prepared prep, Template template) {
        List<Write> inserts = new ArrayList<>();
        List<Write> updates = new ArrayList<>();
        for (Draft d : prep.drafts) {
            if (!d.written()) {
                continue;
            }
            boolean isNew = d.existing() == null;
            Listing state = new Listing(isNew ? UUID.randomUUID() : d.existing().id(), actor.clientId(), p.accountId(), template.id(), d.sku(),
                    d.parentSku(), d.role(), d.title(), d.data(), d.fields(), hash(d.data(), d.role()), d.owner(),
                    p.newStatus() == null ? WorkspaceTypes.ListingStatus.DRAFT : p.newStatus(), ListingSource.IMPORT, null, 0, null, null, null);
            Write write = new Write(state.id(), state, ChangeKind.IMPORT, actor.accountId(), isNew ? 0 : d.existing().version());
            (isNew ? inserts : updates).add(write);
        }
        listings.insertAll(actor.clientId(), inserts);
        List<Write> conflicts = listings.updateAll(actor.clientId(), updates);
        if (events != null) {
            // the rows this import really wrote: the new ones and the updated ones that nobody else changed first (ADR-061)
            java.util.Set<UUID> notWritten = new java.util.HashSet<>();
            conflicts.forEach(w -> notWritten.add(w.id()));
            List<UUID> written = new ArrayList<>();
            inserts.forEach(w -> written.add(w.id()));
            updates.stream().filter(w -> !notWritten.contains(w.id())).forEach(w -> written.add(w.id()));
            if (!written.isEmpty()) {
                events.publishEvent(new WorkspaceEvents.ListingsImported(actor.clientId(), p.accountId(), written, actor.accountId()));
            }
        }
        return conflicts.size();
    }

    /** Where the import says what it wrote (ADR-061); null where nothing listens, as in a test that builds the service by hand. */
    private org.springframework.context.ApplicationEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    private void writeCustom(Actor actor, Params p, Prepared prep, Template template) {
        List<CustomizationWrite> writes = new ArrayList<>();
        prep.customRows.forEach((sku, data) -> {
            CustomizationState old = prep.customCurrent.get(sku);
            String hash = WorkspaceJson.contentHash(new TreeMap<>(data), "CUSTOM");
            if (old == null || !old.contentHash().equals(hash) || prep.action != TemplateAction.USE_EXISTING) {
                writes.add(new CustomizationWrite(sku, template.id(), data, hash, old));
            }
        });
        listings.writeCustomizations(actor.clientId(), p.accountId(), writes);
    }
}
