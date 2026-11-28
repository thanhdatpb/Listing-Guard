package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.ListingStatusService.Block;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService.Verdict;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.CustomizationRow;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.ListingFilter;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.TemplateColumn;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.OutputFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetCell;
import com.thanhdatpb.listingguard.workspace.sheets.SheetRejected;
import com.thanhdatpb.listingguard.workspace.sheets.SheetValidations;
import com.thanhdatpb.listingguard.workspace.sheets.SheetWriter;
import com.thanhdatpb.listingguard.workspace.sheets.TextEncoding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The export of the listings of one Amazon seller account as the account's own template (ADR-063): a copy of the template workbook with the
 * data rows replaced, a tab-delimited text, or text to paste into a sheet. It reads only the listings, the template versions they use and the
 * Amazon Custom rows; the gate is {@link ListingStatusService#verdictsOf}, never a second one. A listing is exported only when it is ready to
 * export, its gate lets it go (or an ADMIN's reason holds for its content) and the person may change it. Exporting a bulk file moves the listing
 * to "exported" with a time and an EXPORT entry in its history; the export log keeps counts and nothing of the content. Nothing is written to a
 * disk, to a log or to another service: the bytes go back to the caller.
 *
 * <p>The preview and the export read the same plan. The export reads it again under the account's lock, so what it writes is what the
 * database holds at that moment, and it stops if the number of listings is no longer the one the person confirmed.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class WorkspaceExports {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceExports.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    /** The most distinct wrong values one column of the confirmation lists. */
    static final int EXAMPLES = 5;

    /** The sheet to export: the listings (bulk file) or the Amazon Custom rows of the same SKUs. */
    public enum Kind {
        BULK, CUSTOM
    }

    /** What the person wants made: an Excel copy of the template, a tab-delimited text file, or text for the clipboard. */
    public enum Medium {
        EXCEL, TXT, CLIPBOARD
    }

    /**
     * @param medium       null: the account's default format
     * @param encoding     null: the configured one; only for TXT
     * @param templateId   null: every version the listings use, one file each; set: only the listings of that version
     * @param confirmCount null at the preview; the number of listings the confirmation showed, at the export
     */
    public record Request(UUID accountId, Kind kind, Medium medium, TextEncoding encoding, UUID templateId, List<UUID> listingIds,
                          boolean checkValues, boolean withHeader, Integer confirmCount, boolean escapeFormulas) {

        public Request {
            listingIds = List.copyOf(new LinkedHashSet<>(listingIds));
            kind = kind == null ? Kind.BULK : kind;
        }

        /** A request that does not escape the cells of the text for a spreadsheet: the export as it was before ADR-064. */
        public Request(UUID accountId, Kind kind, Medium medium, TextEncoding encoding, UUID templateId, List<UUID> listingIds,
                       boolean checkValues, boolean withHeader, Integer confirmCount) {
            this(accountId, kind, medium, encoding, templateId, listingIds, checkValues, withHeader, confirmCount, false);
        }
    }

    /** A selected listing that is not exported, and why: {@code why} is a code of {@code workspace.export.held.<why>}. */
    public record Held(UUID id, String sku, String title, ListingStatus status, String why, Set<Block> blocks) {
    }

    /** The value warnings of one column: how many listings hold a value outside the template's list, and some of those values. */
    public record ValueWarning(String header, int listings, List<String> values) {
    }

    /**
     * What the check of valid values found for one template version. {@code state}: {@code OFF} (not asked), {@code NO_FILE} (the template has no
     * workbook), {@code NONE} (the sheet has no list rule that can be read), {@code CHECKED}, {@code FAILED} (the workbook could not be read).
     */
    public record Validation(String state, int listRules, int unresolved, int extensionRules, int otherRules, List<ValueWarning> warnings) {

        static Validation of(String state) {
            return new Validation(state, 0, 0, 0, 0, List.of());
        }

        public int cells() {
            return warnings.stream().mapToInt(ValueWarning::listings).sum();
        }

        /** True when the check says all that the sheet declares (no rule left unread). */
        public boolean complete() {
            return "CHECKED".equals(state) && unresolved == 0 && extensionRules == 0;
        }
    }

    /** One template version of the export: the listings that go, the ones held, and what is known of them. */
    public record Group(Template template, int listings, int held, int untrademarked, int opened, int orphanChildren, int replacedCells,
                        Validation validation) {
    }

    public record Preview(SellerAccount account, Kind kind, Medium medium, boolean mediumFellBack, String excelProblem, TextEncoding encoding,
                          boolean withHeader, boolean checkValues, List<Group> groups, List<Held> held, int selected, int exportable, int opened,
                          int untrademarked, int missingCustom, int otherVersion, int outside, List<String> problems, boolean gateActive,
                          boolean auditActive, int uncatalogued, boolean escapeFormulas) {

        public boolean canExport() {
            return problems.isEmpty() && exportable > 0;
        }

        public int warnings() {
            return groups.stream().mapToInt(g -> g.validation().cells()).sum();
        }

        /** The cells of a text file whose tab or line break would be replaced by a space; 0 for any other medium. */
        public int replacedCells() {
            return groups.stream().mapToInt(Group::replacedCells).sum();
        }

        public int orphanChildren() {
            return groups.stream().mapToInt(Group::orphanChildren).sum();
        }
    }

    /** A made file. */
    public record ExportFile(String name, String contentType, byte[] bytes, int listings, OutputFormat format) {
    }

    /**
     * What an export made. {@code clipboard} is set for the clipboard text (and {@code files} is empty); {@code replacedCells} counts the cells of
     * a text file whose tab or line break was replaced by a space.
     */
    public record Result(List<ExportFile> files, String clipboard, int exported, int held, int untrademarked, int opened, int warnings,
                         int replacedCells, int conflicts, String downloadName, String contentType, byte[] bytes, int uncatalogued) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceAccounts accounts;
    private final WorkspaceTemplates templates;
    private final WorkspaceListings listings;
    private final ListingStatusService status;
    private final WorkspaceExportLog exportLog;
    private final StandardTargets targets;
    private final WorkspaceProperties properties;
    private final WorkspaceExportProperties exportProperties;
    private final Clock clock;

    public WorkspaceExports(WorkspaceAccess access, WorkspaceAccounts accounts, WorkspaceTemplates templates, WorkspaceListings listings,
                            ListingStatusService status, WorkspaceExportLog exportLog, StandardTargets targets, WorkspaceProperties properties,
                            WorkspaceExportProperties exportProperties, Clock clock) {
        this.access = access;
        this.accounts = accounts;
        this.templates = templates;
        this.listings = listings;
        this.status = status;
        this.exportLog = exportLog;
        this.targets = targets;
        this.properties = properties;
        this.exportProperties = exportProperties;
        this.clock = clock;
    }

    public int maxListings() {
        return exportProperties.maxListings();
    }

    /** The default text encoding of a text file. */
    public TextEncoding defaultEncoding() {
        return exportProperties.encoding();
    }

    // ------------------------------------------------------------------ choosing the listings

    /** The ids of the listings of an account that match a filter, in the order of the list; at most {@code maxListings + 1}. */
    public List<UUID> idsOf(Actor actor, UUID accountId, ListingFilter filter) {
        accounts.find(actor.clientId(), accountId).orElseThrow(() -> new WorkspaceException("account.unknown"));
        ListingFilter withAccount = new ListingFilter(accountId, null, filter.status(), filter.owner(), filter.noOwner(), filter.query());
        return listings.ids(actor.clientId(), withAccount, exportProperties.maxListings() + 1);
    }

    // ------------------------------------------------------------------ the plan

    /** One reading of a request: what would be exported, and the preview that says so. */
    private static final class Plan {
        Preview preview;
        /** the listings that go, by template version, in file order */
        final Map<UUID, List<Listing>> going = new LinkedHashMap<>();
        final Map<UUID, Template> templates = new LinkedHashMap<>();
        final Map<UUID, Verdict> verdicts = new LinkedHashMap<>();
        final Map<UUID, Map<String, String>> customData = new LinkedHashMap<>();
        final Map<UUID, Validation> validations = new LinkedHashMap<>();
        final Map<UUID, Integer> heldByTemplate = new LinkedHashMap<>();
    }

    /** What the export would do, with every reason that stops it. Nothing is written. */
    public Preview preview(Actor actor, Request request) {
        return plan(actor, request).preview;
    }

    private Plan plan(Actor actor, Request request) {
        UUID clientId = actor.clientId();
        SellerAccount account = accounts.find(clientId, request.accountId()).orElseThrow(() -> new WorkspaceException("account.unknown"));
        List<String> problems = new ArrayList<>();
        Plan plan = new Plan();
        if (account.marketplace() != Marketplace.AMAZON) {
            problems.add("etsy");
        }
        if (request.listingIds().isEmpty()) {
            problems.add("none");
        }
        if (request.listingIds().size() > exportProperties.maxListings()) {
            problems.add("too_many");
        }
        List<UUID> wanted = request.listingIds().size() > exportProperties.maxListings() ? List.of() : request.listingIds();
        Map<UUID, Listing> found = listings.findMany(clientId, wanted);
        List<Listing> inAccount = new ArrayList<>();
        int outside = 0;
        for (UUID id : wanted) {
            Listing l = found.get(id);
            if (l == null || !l.accountId().equals(account.id())) {
                outside++;
            } else {
                inAccount.add(l);
            }
        }
        Map<UUID, Verdict> verdicts = status.verdictsOf(clientId, inAccount);
        plan.verdicts.putAll(verdicts);
        Map<String, CustomizationRow> custom = request.kind() == Kind.CUSTOM
                ? listings.customizationRows(clientId, account.id(), inAccount.stream().map(Listing::sku).toList()) : Map.of();

        List<Held> held = new ArrayList<>();
        Map<UUID, List<Listing>> byTemplate = new LinkedHashMap<>();
        int missingCustom = 0;
        int otherVersion = 0;
        for (Listing l : inAccount) {
            Verdict verdict = verdicts.get(l.id());
            UUID templateId = l.templateId();
            if (request.kind() == Kind.CUSTOM && custom.containsKey(l.sku())) {
                templateId = custom.get(l.sku()).templateId();
            }
            String why = null;
            if (!access.canEdit(actor, l.ownerAccountId())) {
                why = "forbidden";
            } else if (l.status() != ListingStatus.READY_TO_EXPORT) {
                why = "not_ready";
            } else if (!verdict.mayGo()) {
                why = "gate";
            }
            if (why != null) {
                held.add(new Held(l.id(), l.sku(), l.title(), l.status(), why, verdict.blocks()));
                plan.heldByTemplate.merge(templateId, 1, Integer::sum);
                continue;
            }
            if (request.kind() == Kind.CUSTOM) {
                CustomizationRow row = custom.get(l.sku());
                if (row == null) {
                    missingCustom++;
                    continue;
                }
                plan.customData.put(l.id(), row.data());
            }
            if (request.templateId() != null && !request.templateId().equals(templateId)) {
                otherVersion++;
                continue;
            }
            byTemplate.computeIfAbsent(templateId, k -> new ArrayList<>()).add(l);
        }
        TemplateKind wantedKind = request.kind() == Kind.BULK ? TemplateKind.AMAZON_BULK : TemplateKind.AMAZON_CUSTOM;
        boolean excelOk = true;
        for (UUID templateId : byTemplate.keySet()) {
            Template template = templates.find(clientId, templateId).orElse(null);
            if (template == null || template.kind() != wantedKind) {
                problems.add("template");
                continue;
            }
            plan.templates.put(templateId, template);
            if (!template.hasFile() || !(template.sourceFormat().equals("XLSX") || template.sourceFormat().equals("XLSM"))) {
                excelOk = false;
            }
        }
        // a listing held back still belongs to a version: the confirmation counts it there
        for (UUID templateId : plan.heldByTemplate.keySet()) {
            if (!plan.templates.containsKey(templateId)) {
                templates.find(clientId, templateId).filter(t -> t.kind() == wantedKind).ifPresent(t -> plan.templates.put(templateId, t));
            }
        }

        // the medium: the person's choice, else the account's default; the default falls back to text when a template has no workbook
        Medium medium = request.medium();
        boolean fellBack = false;
        if (medium == null) {
            medium = account.exportFormat() == ExportFormat.XLSM ? Medium.EXCEL : Medium.TXT;
            if (medium == Medium.EXCEL && !excelOk) {
                medium = Medium.TXT;
                fellBack = true;
            }
        }
        String excelProblem = null;
        if (!excelOk) {
            excelProblem = "nofile";
            if (medium == Medium.EXCEL) {
                problems.add("excel.nofile");
            }
        }
        if (medium == Medium.CLIPBOARD && byTemplate.size() > 1) {
            problems.add("clipboard.versions");
        }
        TextEncoding encoding = request.encoding() == null ? exportProperties.encoding() : request.encoding();

        List<Group> groups = new ArrayList<>();
        int exportable = 0;
        int opened = 0;
        int untrademarked = 0;
        int uncatalogued = 0;
        // the oldest template version first, so the files and the groups of an export come in the same order every time
        List<Map.Entry<UUID, List<Listing>>> entries = new ArrayList<>(byTemplate.entrySet());
        entries.removeIf(e -> plan.templates.get(e.getKey()) == null);
        entries.sort(Comparator.comparingInt(e -> plan.templates.get(e.getKey()).version()));
        for (Map.Entry<UUID, List<Listing>> entry : entries) {
            Template template = plan.templates.get(entry.getKey());
            List<Listing> ordered = inFileOrder(entry.getValue());
            plan.going.put(entry.getKey(), ordered);
            int groupOpened = (int) ordered.stream().filter(l -> verdicts.get(l.id()).opened() != null && !verdicts.get(l.id()).blocks().isEmpty()).count();
            int groupUntrademarked = (int) ordered.stream().filter(l -> !verdicts.get(l.id()).trademarkChecked()).count();
            uncatalogued += (int) ordered.stream().filter(l -> verdicts.get(l.id()).catalogUnchecked()).count();
            Validation validation = request.checkValues() ? validate(clientId, template, ordered, plan) : Validation.of("OFF");
            plan.validations.put(entry.getKey(), validation);
            int replacedCells = 0;
            if (medium == Medium.TXT) {
                // what the text file would change, said before it is made
                boolean[] numeric = numericColumns(template);
                List<List<SheetCell>> rows = new ArrayList<>();
                for (Listing l : ordered) {
                    rows.add(cells(template, plan.customData.containsKey(l.id()) ? plan.customData.get(l.id()) : l.data(), numeric));
                }
                replacedCells = SheetWriter.replacedCells(template.headerRows(), rows);
            }
            groups.add(new Group(template, ordered.size(), plan.heldByTemplate.getOrDefault(entry.getKey(), 0), groupUntrademarked, groupOpened,
                    orphans(ordered), replacedCells, validation));
            exportable += ordered.size();
            opened += groupOpened;
            untrademarked += groupUntrademarked;
        }
        held.sort(Comparator.comparing(Held::sku));
        plan.preview = new Preview(account, request.kind(), medium, fellBack, excelProblem, encoding, request.withHeader(), request.checkValues(),
                groups, held, wanted.size(), exportable, opened, untrademarked, missingCustom, otherVersion, outside, problems,
                status.gateActive(), status.auditActive(), uncatalogued, request.escapeFormulas());
        return plan;
    }

    /** The parents first, each followed by its children, families by SKU. */
    static List<Listing> inFileOrder(Collection<Listing> in) {
        return in.stream().sorted(Comparator.comparing(WorkspaceExports::family)
                .thenComparingInt((Listing l) -> isChild(l) ? 1 : 0).thenComparing(Listing::sku)).toList();
    }

    private static boolean isChild(Listing l) {
        return l.parentSku() != null && !l.parentSku().isEmpty();
    }

    private static String family(Listing l) {
        return isChild(l) ? l.parentSku() : l.sku();
    }

    /** The children whose parent is not among the listings of the file: the seller is told, nothing is changed. */
    private static int orphans(List<Listing> listings) {
        Set<String> skus = new HashSet<>();
        listings.forEach(l -> skus.add(l.sku()));
        return (int) listings.stream().filter(l -> isChild(l) && !skus.contains(l.parentSku())).count();
    }

    // ------------------------------------------------------------------ the values of the template's lists

    private Validation validate(UUID clientId, Template template, List<Listing> ordered, Plan plan) {
        byte[] file = templates.fileBytes(clientId, template.id()).orElse(null);
        if (file == null || template.sheetName() == null) {
            return Validation.of("NO_FILE");
        }
        SheetValidations.Report report;
        try {
            report = SheetValidations.read(file, template.sheetName(), template.firstDataRow(), properties.exportLimits());
        } catch (SheetRejected e) {
            log.info("The list rules of a template of client {} cannot be read ({})", clientId, e.reason());
            return Validation.of("FAILED");
        }
        if (report.empty() && report.unresolved() == 0 && report.extensionRules() == 0) {
            return new Validation("NONE", report.listRules(), 0, 0, report.otherRules(), List.of());
        }
        List<ValueWarning> warnings = new ArrayList<>();
        List<TemplateColumn> columns = template.columns();
        for (TemplateColumn column : columns) {
            Set<String> allowed = report.allowed().get(column.index());
            if (allowed == null) {
                continue;
            }
            Map<String, Integer> wrong = new LinkedHashMap<>();
            int listingsWrong = 0;
            for (Listing l : ordered) {
                Map<String, String> data = plan.customData.containsKey(l.id()) ? plan.customData.get(l.id()) : l.data();
                String value = data.get(column.key());
                if (value != null && !SheetValidations.invalid(allowed, List.of(value)).isEmpty()) {
                    listingsWrong++;
                    wrong.merge(value.strip(), 1, Integer::sum);
                }
            }
            if (listingsWrong > 0) {
                warnings.add(new ValueWarning(column.header().isBlank() ? column.key() : column.header(), listingsWrong,
                        wrong.keySet().stream().limit(EXAMPLES).toList()));
            }
        }
        return new Validation("CHECKED", report.listRules(), report.unresolved(), report.extensionRules(), report.otherRules(), warnings);
    }

    // ------------------------------------------------------------------ the rows

    /** The cells of one listing in the order of the template's columns. */
    static List<SheetCell> cells(Template template, Map<String, String> data, boolean[] numeric) {
        List<TemplateColumn> columns = template.columns();
        List<SheetCell> cells = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            String value = data.get(columns.get(i).key());
            if (value == null || value.isEmpty()) {
                cells.add(SheetCell.blank());
            } else if (numeric[i]) {
                cells.add(SheetCell.numeric(value).orElseGet(() -> SheetCell.text(value)));
            } else {
                cells.add(SheetCell.text(value));
            }
        }
        return cells;
    }

    /**
     * Which columns of an Amazon template are written as numbers when their value is a plain number: the columns the person gave a price or a
     * whole number (ADR-063 §2). A column without a standard field is written as text, even when its value looks like a number.
     */
    boolean[] numericColumns(Template template) {
        return numericColumns(template, targets);
    }

    static boolean[] numericColumns(Template template, StandardTargets targets) {
        boolean[] numeric = new boolean[template.columns().size()];
        for (int i = 0; i < numeric.length; i++) {
            String target = template.mapping().targets().get(template.columns().get(i).key());
            numeric[i] = target != null && targets.find(Marketplace.AMAZON, target)
                    .map(t -> t.kind() == StandardTargets.Kind.PRICE || t.kind() == StandardTargets.Kind.INTEGER).orElse(false);
        }
        return numeric;
    }

    // ------------------------------------------------------------------ the export

    /**
     * Makes the files or the text, moves the exported listings and writes the log, all in one transaction under the account's lock. A bulk
     * export moves each listing that goes from "ready to export" to "exported"; a Custom export changes no status. Nothing is changed when a
     * file cannot be made.
     *
     * @throws WorkspaceException {@code export.blocked} (the plan has a problem), {@code export.changed} (the listings that go are no longer
     *                            the ones the person confirmed), or the code of a refused file
     */
    @Transactional
    public Result export(Actor actor, Request request) {
        UUID clientId = actor.clientId();
        listings.lockAccount(request.accountId());
        Plan plan = plan(actor, request);
        Preview preview = plan.preview;
        if (!preview.canExport()) {
            throw new WorkspaceException("export.blocked");
        }
        if (request.confirmCount() == null || request.confirmCount() != preview.exportable()) {
            throw new WorkspaceException("export.changed");
        }
        Instant now = clock.instant();
        String stamp = STAMP.format(now);
        String slug = slug(preview.account().name());
        String kindWord = preview.kind() == Kind.BULK ? "bulk" : "custom";
        List<ExportFile> files = new ArrayList<>();
        StringBuilder clipboard = new StringBuilder();
        int exported = 0;
        int conflicts = 0;
        int replaced = 0;
        int untrademarked = 0;
        int uncatalogued = 0;
        int opened = 0;
        int warnings = 0;
        for (Map.Entry<UUID, List<Listing>> entry : plan.going.entrySet()) {
            Template template = plan.templates.get(entry.getKey());
            List<Listing> going = entry.getValue();
            if (preview.kind() == Kind.BULK) {
                // the listings leave "ready" first, guarded by their version: one that was changed meanwhile is not in the file
                List<Listing> released = new ArrayList<>();
                for (Listing l : going) {
                    if (listings.release(clientId, l.id(), ListingStatus.EXPORTED, l.version(), actor.accountId())) {
                        released.add(l);
                    } else {
                        conflicts++;
                    }
                }
                going = released;
            }
            if (going.isEmpty()) {
                continue;
            }
            boolean[] numeric = numericColumns(template);
            List<List<SheetCell>> rows = new ArrayList<>(going.size());
            for (Listing l : going) {
                rows.add(cells(template, plan.customData.containsKey(l.id()) ? plan.customData.get(l.id()) : l.data(), numeric));
            }
            OutputFormat format;
            String encodingCode = null;
            int groupReplaced = 0;
            try {
                switch (preview.medium()) {
                    case EXCEL -> {
                        byte[] file = templates.fileBytes(clientId, template.id()).orElseThrow(() -> new WorkspaceException("export.nofile"));
                        byte[] bytes = SheetWriter.workbook(file, template.sheetName(), template.firstDataRow(), rows, properties.exportLimits());
                        format = "XLSM".equals(template.sourceFormat()) ? OutputFormat.XLSM : OutputFormat.XLSX;
                        files.add(new ExportFile(slug + "-" + kindWord + "-v" + template.version() + "-" + stamp
                                + (format == OutputFormat.XLSM ? ".xlsm" : ".xlsx"), format == OutputFormat.XLSM
                                ? "application/vnd.ms-excel.sheet.macroEnabled.12"
                                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes, going.size(), format));
                    }
                    case TXT -> {
                        SheetWriter.TextFile text = SheetWriter.delimited(template.headerRows(), template.columns().size(), rows,
                                preview.encoding());
                        format = OutputFormat.TXT;
                        encodingCode = preview.encoding().code();
                        groupReplaced = text.replacedCells();
                        files.add(new ExportFile(slug + "-" + kindWord + "-v" + template.version() + "-" + stamp + ".txt",
                                "text/plain; charset=" + preview.encoding().charset().name(), text.bytes(), going.size(), format));
                    }
                    case CLIPBOARD -> {
                        format = OutputFormat.CLIPBOARD;
                        clipboard.append(SheetWriter.clipboard(preview.withHeader() ? template.headerRows() : List.of(), rows, preview.escapeFormulas()));
                    }
                    default -> throw new IllegalStateException();
                }
            } catch (SheetRejected e) {
                throw new WorkspaceException("export.sheet." + e.reason().name().toLowerCase(Locale.ROOT));
            }
            replaced += groupReplaced;
            Verdict[] groupVerdicts = going.stream().map(l -> plan.verdicts.get(l.id())).toArray(Verdict[]::new);
            int groupUntrademarked = (int) java.util.Arrays.stream(groupVerdicts).filter(v -> !v.trademarkChecked()).count();
            int groupUncatalogued = (int) java.util.Arrays.stream(groupVerdicts).filter(Verdict::catalogUnchecked).count();
            int groupOpened = (int) java.util.Arrays.stream(groupVerdicts).filter(v -> v.opened() != null && !v.blocks().isEmpty()).count();
            int groupWarnings = plan.validations.getOrDefault(entry.getKey(), Validation.of("OFF")).cells();
            exportLog.add(clientId, new WorkspaceExportLog.Entry(0, preview.account().id(), template.id(), template.version(), template.kind(),
                    format, encodingCode, going.size(), plan.heldByTemplate.getOrDefault(entry.getKey(), 0), groupOpened, groupUntrademarked,
                    groupWarnings, groupReplaced, actor.accountId(), now, groupUncatalogued));
            exported += going.size();
            untrademarked += groupUntrademarked;
            uncatalogued += groupUncatalogued;
            opened += groupOpened;
            warnings += groupWarnings;
        }
        String downloadName = null;
        String contentType = null;
        byte[] bytes = null;
        if (files.size() == 1) {
            downloadName = files.get(0).name();
            contentType = files.get(0).contentType();
            bytes = files.get(0).bytes();
        } else if (files.size() > 1) {
            downloadName = slug + "-" + kindWord + "-" + stamp + ".zip";
            contentType = "application/zip";
            bytes = zip(files);
        }
        log.info("Workspace export by account {} of client {}: {} listings in {} files, {} held back", actor.accountId(), clientId, exported,
                files.size(), preview.held().size());
        return new Result(List.copyOf(files), preview.medium() == Medium.CLIPBOARD ? clipboard.toString() : null, exported,
                preview.held().size(), untrademarked, opened, warnings, replaced, conflicts, downloadName, contentType, bytes, uncatalogued);
    }

    private static byte[] zip(List<ExportFile> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ExportFile file : files) {
                zip.putNextEntry(new ZipEntry(file.name()));
                zip.write(file.bytes());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("A zip in memory cannot fail.", e);
        }
        return out.toByteArray();
    }

    /** A name for a file: lower-case letters, digits and hyphens of the account's name. */
    static String slug(String name) {
        String clean = java.text.Normalizer.normalize(name.toLowerCase(Locale.ROOT).replace('đ', 'd'), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return clean.isEmpty() ? "account" : clean.length() > 40 ? clean.substring(0, 40) : clean;
    }

    // ------------------------------------------------------------------ the Etsy listing posted by hand, and the live status

    public enum PostResult {
        /** moved to live */
        POSTED,
        /** moved to live after an ADMIN's reason held for this content */
        POSTED_OPENED,
        /** held back by the gate */
        BLOCKED,
        /** not "ready to export" */
        NOT_READY,
        FORBIDDEN,
        CONFLICT,
        /** an Amazon listing: it goes out as a file */
        NOT_ETSY,
        UNKNOWN
    }

    public record PostOutcome(PostResult result, Set<Block> blocks) {
    }

    /**
     * "Posted" for an Etsy listing: a person posted it by hand with the copy panel. It goes from "ready to export" to live through the same gate as
     * an export, gets the time and an EXPORT entry in its history, and one row in the export log (format {@code ETSY_POSTED}).
     */
    @Transactional
    public PostOutcome markPosted(Actor actor, UUID id) {
        Listing l = listings.find(actor.clientId(), id).orElse(null);
        if (l == null) {
            return new PostOutcome(PostResult.UNKNOWN, Set.of());
        }
        SellerAccount account = accounts.find(actor.clientId(), l.accountId()).orElseThrow();
        if (account.marketplace() != Marketplace.ETSY) {
            return new PostOutcome(PostResult.NOT_ETSY, Set.of());
        }
        if (!access.canEdit(actor, l.ownerAccountId())) {
            return new PostOutcome(PostResult.FORBIDDEN, Set.of());
        }
        if (l.status() != ListingStatus.READY_TO_EXPORT) {
            return new PostOutcome(PostResult.NOT_READY, Set.of());
        }
        Verdict verdict = status.verdictsOf(actor.clientId(), List.of(l)).get(id);
        if (!verdict.mayGo()) {
            return new PostOutcome(PostResult.BLOCKED, verdict.blocks());
        }
        listings.lockAccount(l.accountId());
        if (!listings.release(actor.clientId(), id, ListingStatus.LIVE, l.version(), actor.accountId())) {
            return new PostOutcome(PostResult.CONFLICT, Set.of());
        }
        Template template = templates.find(actor.clientId(), l.templateId()).orElse(null);
        boolean opened = verdict.opened() != null && !verdict.blocks().isEmpty();
        exportLog.add(actor.clientId(), new WorkspaceExportLog.Entry(0, l.accountId(), template == null ? null : template.id(),
                template == null ? null : template.version(), TemplateKind.ETSY_FIELDS, OutputFormat.ETSY_POSTED, null, 1, 0, opened ? 1 : 0,
                verdict.trademarkChecked() ? 0 : 1, 0, 0, actor.accountId(), clock.instant(), verdict.catalogUnchecked() ? 1 : 0));
        log.info("Workspace listing {} of client {} posted by account {}", id, actor.clientId(), actor.accountId());
        return new PostOutcome(opened ? PostResult.POSTED_OPENED : PostResult.POSTED, verdict.blocks());
    }

    /** "On sale" for a listing that was exported: a person says the marketplace has it. False when it is not exported, not the person's, or changed. */
    @Transactional
    public boolean markLive(Actor actor, UUID id) {
        Listing l = listings.find(actor.clientId(), id).orElseThrow(() -> new WorkspaceException("listing.unknown"));
        if (!access.canEdit(actor, l.ownerAccountId())) {
            throw new WorkspaceException("forbidden");
        }
        return l.status() == ListingStatus.EXPORTED && listings.markLive(actor.clientId(), id, l.version(), actor.accountId());
    }
}
