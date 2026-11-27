package com.thanhdatpb.listingguard.api.console.workspace;

import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.MappingColumn;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.MappingView;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Plan;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Result;
import com.thanhdatpb.listingguard.api.workspace.ImportStages;
import com.thanhdatpb.listingguard.api.workspace.ImportStages.Stage;
import com.thanhdatpb.listingguard.api.workspace.ListingImportService;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets.Kind;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets.Target;
import com.thanhdatpb.listingguard.api.workspace.TemplateService;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.Created;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.SourceFile;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccounts;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceProperties;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTemplates;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.HeaderGuess;
import com.thanhdatpb.listingguard.workspace.sheets.SheetFormat;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import com.thanhdatpb.listingguard.workspace.sheets.SheetLimits;
import com.thanhdatpb.listingguard.workspace.sheets.SheetReader;
import com.thanhdatpb.listingguard.workspace.sheets.SheetRejected;
import com.thanhdatpb.listingguard.workspace.sheets.WorkbookInfo;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The steps that bring a sheet into the workspace, and the same steps that make an Amazon template (ADR-059): choose the account, the kind
 * of sheet and the responsible person and upload a file or paste cells; confirm the sheet, the row of field names and the SKU column;
 * confirm what each column is; see how many rows are new, updated, unchanged or wrong; write. In {@code mode=template} the last step
 * saves the template (ADMIN only) and writes no listing.
 *
 * <p>The uploaded file waits in {@code workspace_import_stage} (its own, expiring table) between the steps, so a 20 MB file never travels
 * through a form. Each page is a plain {@code GET} or {@code POST} that works without a script. A column is never given a meaning the
 * person did not confirm: the guess only fills the form in and says it is a guess. Every value is printed with {@code th:text}.
 */
@Controller
@RequestMapping("/console/workspace")
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.workspace.enabled:false}")
public class ConsoleWorkspaceImportController {

    static final int PREVIEW_COLUMNS = 12;
    static final int PREVIEW_ROWS = 12;
    private static final int ISSUES_SHOWN = 200;
    private static final String IMPORT = "import";
    private static final String TEMPLATE = "template";

    public record PreviewRow(int number, List<String> cells) {
    }

    public record ColumnOption(int index, String label) {
    }

    public record MappingRow(int index, String letters, String header, String target, String origin, String originLabel) {
    }

    public record IssueRow(int line, String sku, String text) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceAccounts accounts;
    private final WorkspaceTemplates templates;
    private final ImportStages stages;
    private final ListingImportService imports;
    private final TemplateService templateService;
    private final ConsoleAccountRoles roles;
    private final ConsoleMessages messages;
    private final WorkspaceProperties properties;
    private final StandardTargets targets;

    public ConsoleWorkspaceImportController(WorkspaceAccess access, WorkspaceAccounts accounts, WorkspaceTemplates templates,
                                            ImportStages stages, ListingImportService imports, TemplateService templateService,
                                            ConsoleAccountRoles roles, ConsoleMessages messages, WorkspaceProperties properties,
                                            StandardTargets targets) {
        this.access = access;
        this.accounts = accounts;
        this.templates = templates;
        this.stages = stages;
        this.imports = imports;
        this.templateService = templateService;
        this.roles = roles;
        this.messages = messages;
        this.properties = properties;
        this.targets = targets;
    }

    private Actor actor(ConsoleUser user) {
        return access.actor(user).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    private Actor actorFor(ConsoleUser user, String mode) {
        Actor actor = actor(user);
        if (TEMPLATE.equals(mode) && !access.canManage(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return actor;
    }

    private static String mode(Map<String, String> params) {
        return TEMPLATE.equals(params.get("mode")) ? TEMPLATE : IMPORT;
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private static UUID uuid(String text) {
        try {
            return text == null || text.isBlank() ? null : UUID.fromString(text.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int number(String text, int fallback) {
        try {
            return text == null || text.isBlank() ? fallback : Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new WorkspaceException("params.invalid");
        }
    }

    // ------------------------------------------------------------------ step 1: choose and upload

    @GetMapping("/import")
    public String start(@AuthenticationPrincipal ConsoleUser user, @RequestParam Map<String, String> params, Model model,
                        HttpServletResponse response) {
        noStore(response);
        String mode = mode(params);
        Actor actor = actorFor(user, mode);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        model.addAttribute("mode", mode);
        model.addAttribute("admin", actor.admin());
        model.addAttribute("sellerAccounts", accounts.list(actor.clientId()).stream().filter(SellerAccount::enabled).toList());
        model.addAttribute("owners", roles.emails(actor.clientId()));
        model.addAttribute("me", actor.accountId());
        model.addAttribute("selectedAccount", params.getOrDefault("account", ""));
        model.addAttribute("selectedKind", params.getOrDefault("kind", ""));
        List<String[]> kinds = new ArrayList<>();
        for (TemplateKind kind : TemplateKind.values()) {
            if (!TEMPLATE.equals(mode) || kind != TemplateKind.ETSY_FIELDS) {
                kinds.add(new String[] {kind.name(), labels.kind(kind)});
            }
        }
        model.addAttribute("kinds", kinds);
        List<String[]> statuses = new ArrayList<>();
        for (ListingStatus status : ListingStatus.values()) {
            statuses.add(new String[] {status.name(), labels.status(status)});
        }
        model.addAttribute("statuses", statuses);
        model.addAttribute("maxRows", properties.importMaxRows());
        model.addAttribute("maxMegabytes", (properties.importMaxBytes().toBytes() + (1L << 20) - 1) >> 20);
        return "console/workspace-import";
    }

    @PostMapping("/import/upload")
    public String upload(@AuthenticationPrincipal ConsoleUser user, @RequestParam Map<String, String> params,
                         @RequestParam(name = "file", required = false) MultipartFile file,
                         @RequestParam(name = "text", defaultValue = "") String text, RedirectAttributes redirect) throws IOException {
        String mode = mode(params);
        Actor actor = actorFor(user, mode);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        UUID account = uuid(params.get("account"));
        try {
            if (account == null || accounts.find(actor.clientId(), account).isEmpty()) {
                throw new WorkspaceException("account.unknown");
            }
            if (WorkspaceTypes.parse(TemplateKind.class, params.get("kind")).isEmpty()) {
                throw new WorkspaceException("params.invalid");
            }
            byte[] bytes;
            String name = null;
            if (file != null && !file.isEmpty()) {
                bytes = file.getBytes();
                name = file.getOriginalFilename();
            } else if (!text.isBlank()) {
                bytes = text.getBytes(StandardCharsets.UTF_8);
            } else {
                throw new WorkspaceException("file.required");
            }
            SheetLimits limits = properties.limits(TEMPLATE.equals(mode));
            SheetReader.inspect(bytes, limits);
            UUID stage = stages.put(actor.clientId(), actor.accountId(), name, bytes);
            Map<String, String> carry = new LinkedHashMap<>();
            carry.put("stage", stage.toString());
            copy(params, carry, "mode", "account", "kind", "owner", "status");
            return "redirect:" + url("/console/workspace/import/sheet", carry);
        } catch (WorkspaceException e) {
            redirect.addFlashAttribute("error", labels.error(e.code()));
        } catch (SheetRejected e) {
            redirect.addFlashAttribute("error", labels.sheetProblem(e.reason()));
        }
        Map<String, String> back = new LinkedHashMap<>();
        copy(params, back, "mode", "account", "kind");
        return "redirect:" + url("/console/workspace/import", back);
    }

    // ------------------------------------------------------------------ step 2: sheet, field row, SKU column

    @GetMapping("/import/sheet")
    public String sheet(@AuthenticationPrincipal ConsoleUser user, @RequestParam Map<String, String> params, Model model,
                        RedirectAttributes redirect, HttpServletResponse response) {
        noStore(response);
        String mode = mode(params);
        Actor actor = actorFor(user, mode);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        Stage stage = stage(actor, params);
        if (stage == null) {
            redirect.addFlashAttribute("error", labels.error("stage.expired"));
            return "redirect:/console/workspace/import";
        }
        try {
            SellerAccount account = accounts.find(actor.clientId(), uuid(params.get("account"))).orElseThrow(() -> new WorkspaceException("account.unknown"));
            WorkbookInfo info = SheetReader.inspect(stage.bytes(), properties.limits(TEMPLATE.equals(mode)));
            WorkbookInfo.SheetInfo chosen = info.sheets().stream().filter(s -> s.name().equals(params.getOrDefault("sheet", ""))).findFirst()
                    .orElse(guessSheet(info));
            String sheet = chosen.name();
            HeaderGuess guess = HeaderGuess.of(chosen.preview());
            boolean fieldRowGiven = params.containsKey("fieldRow") && params.get("sheet") != null && params.get("sheet").equals(sheet);
            int fieldRow = fieldRowGiven ? number(params.get("fieldRow"), guess.fieldRow()) : guess.fieldRow();
            int firstDataRow = fieldRowGiven ? number(params.get("firstDataRow"), fieldRow + 1) : guess.firstDataRow();
            SheetGrid grid = fieldRow <= SheetReader.PREVIEW_ROWS ? chosen.preview()
                    : SheetReader.read(stage.bytes(), sheet, properties.limits(TEMPLATE.equals(mode)));
            List<String> headerCells = grid.row(fieldRow);
            List<ColumnOption> columns = new ArrayList<>();
            int skuGuess = -1;
            for (int i = 0; i < headerCells.size(); i++) {
                String header = headerCells.get(i);
                if (header == null || header.isBlank()) {
                    continue;
                }
                columns.add(new ColumnOption(i, WorkspaceTemplates.letters(i) + " — " + header));
                if (skuGuess < 0 && targets.guess(account.marketplace(), header).filter(t -> t.kind() == Kind.SKU).isPresent()) {
                    skuGuess = i;
                }
            }
            String skuGiven = params.get("skuCol");
            int skuCol = skuGiven != null && !skuGiven.isBlank() ? number(skuGiven, skuGuess) : skuGuess;
            List<PreviewRow> preview = new ArrayList<>();
            for (int r = 1; r <= Math.min(PREVIEW_ROWS, Math.max(grid.rowCount(), 1)); r++) {
                List<String> cells = grid.row(r);
                preview.add(new PreviewRow(r, cells.subList(0, Math.min(cells.size(), PREVIEW_COLUMNS))));
            }
            model.addAttribute("mode", mode);
            model.addAttribute("carry", carry(params, "stage", "mode", "account", "kind", "owner", "status"));
            model.addAttribute("sheets", info.sheets().stream().map(WorkbookInfo.SheetInfo::name).toList());
            model.addAttribute("sheet", sheet);
            model.addAttribute("preview", preview);
            model.addAttribute("fieldRow", fieldRow);
            model.addAttribute("firstDataRow", firstDataRow);
            model.addAttribute("fieldRowGuessed", !fieldRowGiven);
            model.addAttribute("guessConfident", guess.confident());
            model.addAttribute("sheetGuessed", params.get("sheet") == null || params.get("sheet").isBlank());
            model.addAttribute("columns", columns);
            model.addAttribute("skuCol", skuCol);
            model.addAttribute("skuGuessed", skuGiven == null || skuGiven.isBlank());
            model.addAttribute("macro", info.macroProject());
            model.addAttribute("format", info.format().name());
            model.addAttribute("fileName", stage.fileName());
            model.addAttribute("admin", actor.admin());
            return "console/workspace-sheet";
        } catch (WorkspaceException e) {
            redirect.addFlashAttribute("error", labels.error(e.code()));
        } catch (SheetRejected e) {
            redirect.addFlashAttribute("error", labels.sheetProblem(e.reason()));
        }
        return "redirect:/console/workspace/import";
    }

    /** The sheet that looks most like a flat file: the one whose first rows hold the most field-like cells; the first sheet otherwise. */
    private static WorkbookInfo.SheetInfo guessSheet(WorkbookInfo info) {
        WorkbookInfo.SheetInfo best = info.sheets().get(0);
        int bestWidth = -1;
        for (WorkbookInfo.SheetInfo sheet : info.sheets()) {
            HeaderGuess guess = HeaderGuess.of(sheet.preview());
            int width = guess.confident() ? sheet.preview().row(guess.fieldRow()).size() : 0;
            if (width > bestWidth) {
                best = sheet;
                bestWidth = width;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ step 3: columns

    @GetMapping("/import/mapping")
    public String mapping(@AuthenticationPrincipal ConsoleUser user, @RequestParam Map<String, String> params, Model model,
                          RedirectAttributes redirect, HttpServletResponse response) {
        noStore(response);
        String mode = mode(params);
        Actor actor = actorFor(user, mode);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        Stage stage = stage(actor, params);
        if (stage == null) {
            redirect.addFlashAttribute("error", labels.error("stage.expired"));
            return "redirect:/console/workspace/import";
        }
        try {
            Params base = base(actor, params);
            SheetGrid grid = grid(stage, base.sheet(), mode);
            MappingView view = imports.mappingView(actor, base.accountId(), base.kind(), grid, base.fieldRow());
            int skuCol = number(params.get("skuCol"), -1);
            List<MappingRow> rows = new ArrayList<>();
            for (MappingColumn column : view.columns()) {
                String target = column.target();
                String origin = column.origin();
                if (skuCol >= 0 && "sku".equals(target) && column.index() != skuCol) {
                    target = "";
                    origin = "none";
                }
                if (column.index() == skuCol) {
                    target = "sku";
                    origin = params.get("skuCol") == null || "guessed".equals(origin) ? origin : "chosen";
                    if ("none".equals(origin)) {
                        origin = "chosen";
                    }
                }
                rows.add(new MappingRow(column.index(), WorkspaceTemplates.letters(column.index()), column.header(), target, origin,
                        labels.origin(origin)));
            }
            boolean readOnly = !actor.admin() && base.kind() != TemplateKind.ETSY_FIELDS;
            if (readOnly && view.current() == null) {
                throw new WorkspaceException("template.admin_required");
            }
            List<String[]> options = new ArrayList<>();
            for (String key : templateService.allowedTargets(base.kind())) {
                options.add(new String[] {key, labels.target(key)});
            }
            List<Object[]> separators = new ArrayList<>();
            if (base.kind() == TemplateKind.AMAZON_BULK || base.kind() == TemplateKind.ETSY_FIELDS) {
                for (Target target : targets.of(accounts.find(actor.clientId(), base.accountId()).orElseThrow().marketplace())) {
                    if (target.kind() == Kind.LIST) {
                        String chosen = params.get("sep." + target.key());
                        if (chosen == null && view.current() != null) {
                            chosen = view.current().mapping().separators().get(target.key());
                        }
                        if (chosen == null) {
                            chosen = target.defaultSeparator() == null ? "" : target.defaultSeparator();
                        }
                        separators.add(new Object[] {target.key(), labels.target(target.key()), chosen});
                    }
                }
            }
            List<String[]> separatorOptions = new ArrayList<>();
            separatorOptions.add(new String[] {"", messages.get("workspace.separator.none")});
            for (String key : targets.separatorKeys()) {
                separatorOptions.add(new String[] {key, labels.separator(key)});
            }
            model.addAttribute("mode", mode);
            model.addAttribute("carry", carry(params, "stage", "mode", "account", "kind", "owner", "status", "sheet", "fieldRow", "firstDataRow",
                    "skuCol"));
            model.addAttribute("rows", rows);
            model.addAttribute("options", options);
            model.addAttribute("separators", separators);
            model.addAttribute("separatorOptions", separatorOptions);
            model.addAttribute("readOnly", readOnly);
            model.addAttribute("admin", actor.admin());
            model.addAttribute("hasTemplate", view.current() != null);
            model.addAttribute("kindLabel", labels.kind(base.kind()));
            return "console/workspace-mapping";
        } catch (WorkspaceException e) {
            redirect.addFlashAttribute("error", labels.error(e.code()));
        } catch (SheetRejected e) {
            redirect.addFlashAttribute("error", labels.sheetProblem(e.reason()));
        }
        return "redirect:" + url("/console/workspace/import/sheet", carry(params, "stage", "mode", "account", "kind", "owner", "status", "sheet"));
    }

    // ------------------------------------------------------------------ step 4: preview, then write

    @PostMapping("/import/preview")
    public String preview(@AuthenticationPrincipal ConsoleUser user, @RequestParam Map<String, String> params, Model model,
                          RedirectAttributes redirect, HttpServletResponse response) {
        noStore(response);
        Actor actor = actorFor(user, IMPORT);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        Stage stage = stage(actor, params);
        if (stage == null) {
            redirect.addFlashAttribute("error", labels.error("stage.expired"));
            return "redirect:/console/workspace/import";
        }
        try {
            Params p = full(actor, params);
            SheetGrid grid = grid(stage, p.sheet(), IMPORT);
            Plan plan = imports.plan(actor, p, grid);
            List<IssueRow> issues = new ArrayList<>();
            plan.issues().stream().limit(ISSUES_SHOWN).forEach(i -> issues.add(new IssueRow(i.line(), i.sku(), labels.issue(i.code()))));
            model.addAttribute("plan", plan);
            model.addAttribute("issues", issues);
            model.addAttribute("moreIssues", Math.max(0, plan.issues().size() - ISSUES_SHOWN));
            model.addAttribute("blocking", plan.blocking() == null ? null : labels.error(plan.blocking()));
            model.addAttribute("actionLabel", labels.action(plan.templateAction().name()));
            model.addAttribute("customize", p.kind() == TemplateKind.AMAZON_CUSTOM);
            model.addAttribute("carry", carryAll(params));
            model.addAttribute("kindLabel", labels.kind(p.kind()));
            return "console/workspace-preview";
        } catch (WorkspaceException e) {
            redirect.addFlashAttribute("error", labels.error(e.code()));
        } catch (SheetRejected e) {
            redirect.addFlashAttribute("error", labels.sheetProblem(e.reason()));
        }
        return "redirect:" + url("/console/workspace/import/mapping", carry(params, "stage", "mode", "account", "kind", "owner", "status", "sheet",
                "fieldRow", "firstDataRow", "skuCol"));
    }

    @PostMapping("/import/commit")
    public String commit(@AuthenticationPrincipal ConsoleUser user, @RequestParam Map<String, String> params, RedirectAttributes redirect) {
        Actor actor = actorFor(user, IMPORT);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        Stage stage = stage(actor, params);
        if (stage == null) {
            redirect.addFlashAttribute("error", labels.error("stage.expired"));
            return "redirect:/console/workspace/import";
        }
        try {
            Params p = full(actor, params);
            SheetGrid grid = grid(stage, p.sheet(), IMPORT);
            SheetFormat format = SheetReader.detect(stage.bytes(), properties.limits(false));
            Result result = imports.commit(actor, p, grid, new SourceFile(format, stage.fileName(), stage.bytes()), stage.id());
            redirect.addFlashAttribute("notice", messages.get("workspace.import.done", result.counts().created(), result.counts().updated(),
                    result.counts().unchanged(), result.counts().errors()));
            if (result.conflicts() > 0) {
                redirect.addFlashAttribute("error", messages.get("workspace.import.conflicts", result.conflicts()));
            }
            return "redirect:/console/workspace";
        } catch (WorkspaceException e) {
            redirect.addFlashAttribute("error", labels.error(e.code()));
        } catch (SheetRejected e) {
            redirect.addFlashAttribute("error", labels.sheetProblem(e.reason()));
        }
        return "redirect:" + url("/console/workspace/import/mapping", carry(params, "stage", "mode", "account", "kind", "owner", "status", "sheet",
                "fieldRow", "firstDataRow", "skuCol"));
    }

    @PostMapping("/import/save-template")
    public String saveTemplate(@AuthenticationPrincipal ConsoleUser user, @RequestParam Map<String, String> params, RedirectAttributes redirect) {
        Actor actor = actorFor(user, TEMPLATE);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        Stage stage = stage(actor, params);
        if (stage == null) {
            redirect.addFlashAttribute("error", labels.error("stage.expired"));
            return "redirect:/console/workspace/import?mode=template";
        }
        try {
            Params p = full(actor, params);
            SheetGrid grid = grid(stage, p.sheet(), TEMPLATE);
            SheetFormat format = SheetReader.detect(stage.bytes(), properties.limits(true));
            Created created = templateService.createFromFile(actor, p.accountId(), p.kind(), grid, p.fieldRow(), p.firstDataRow(),
                    new SourceFile(format, stage.fileName(), stage.bytes()), p.targets(), p.separators());
            stages.delete(actor.clientId(), stage.id());
            redirect.addFlashAttribute("notice", created.created()
                    ? messages.get("workspace.template.saved", created.template().version())
                    : messages.get("workspace.template.unchanged", created.template().version()));
            return "redirect:/console/workspace/accounts";
        } catch (WorkspaceException e) {
            redirect.addFlashAttribute("error", labels.error(e.code()));
        } catch (SheetRejected e) {
            redirect.addFlashAttribute("error", labels.sheetProblem(e.reason()));
        }
        return "redirect:" + url("/console/workspace/import/mapping", carry(params, "stage", "mode", "account", "kind", "sheet", "fieldRow",
                "firstDataRow", "skuCol"));
    }

    @PostMapping("/import/cancel")
    public String cancel(@AuthenticationPrincipal ConsoleUser user, @RequestParam Map<String, String> params, RedirectAttributes redirect) {
        Actor actor = actor(user);
        Stage stage = stage(actor, params);
        if (stage != null) {
            stages.delete(actor.clientId(), stage.id());
        }
        redirect.addFlashAttribute("notice", messages.get("workspace.import.cancelled"));
        return "redirect:/console/workspace";
    }

    // ------------------------------------------------------------------ helpers

    private Stage stage(Actor actor, Map<String, String> params) {
        UUID id = uuid(params.get("stage"));
        return id == null ? null : stages.get(actor.clientId(), actor.accountId(), id).orElse(null);
    }

    private SheetGrid grid(Stage stage, String sheet, String mode) {
        return SheetReader.read(stage.bytes(), sheet, properties.limits(TEMPLATE.equals(mode)));
    }

    /** The choices of steps 1 and 2: account, kind, responsible person, status of new rows, sheet and rows. */
    private Params base(Actor actor, Map<String, String> params) {
        UUID account = uuid(params.get("account"));
        TemplateKind kind = WorkspaceTypes.parse(TemplateKind.class, params.get("kind")).orElseThrow(() -> new WorkspaceException("params.invalid"));
        String sheet = params.getOrDefault("sheet", "");
        int fieldRow = number(params.get("fieldRow"), 0);
        int firstDataRow = number(params.get("firstDataRow"), fieldRow + 1);
        if (account == null || fieldRow < 1) {
            throw new WorkspaceException("params.invalid");
        }
        return new Params(account, kind, uuid(params.get("owner")), WorkspaceTypes.parse(ListingStatus.class, params.get("status")).orElse(ListingStatus.DRAFT),
                sheet, fieldRow, firstDataRow, Map.of(), Map.of());
    }

    /** Everything the person chose, including what each column is ({@code col.<i>}) and the separators ({@code sep.<target>}). */
    private Params full(Actor actor, Map<String, String> params) {
        Params base = base(actor, params);
        Map<Integer, String> chosen = new LinkedHashMap<>();
        Map<String, String> separators = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (key.startsWith("col.")) {
                int index;
                try {
                    index = Integer.parseInt(key.substring(4));
                } catch (NumberFormatException e) {
                    throw new WorkspaceException("params.invalid");
                }
                if (value != null && !value.isBlank()) {
                    chosen.put(index, value.strip());
                }
            } else if (key.startsWith("sep.") && value != null && !value.isBlank()) {
                separators.put(key.substring(4), value.strip());
            }
        });
        return new Params(base.accountId(), base.kind(), base.defaultOwner(), base.newStatus(), base.sheet(), base.fieldRow(), base.firstDataRow(),
                chosen, separators);
    }

    private static void copy(Map<String, String> from, Map<String, String> to, String... keys) {
        for (String key : keys) {
            String value = from.get(key);
            if (value != null && !value.isBlank()) {
                to.put(key, value);
            }
        }
    }

    private static Map<String, String> carry(Map<String, String> params, String... keys) {
        Map<String, String> carry = new LinkedHashMap<>();
        copy(params, carry, keys);
        return carry;
    }

    /** Every field of a form but the CSRF token, to put back in the next form as hidden fields. */
    private static Map<String, String> carryAll(Map<String, String> params) {
        Map<String, String> carry = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (!key.equals("_csrf") && value != null) {
                carry.put(key, value);
            }
        });
        return carry;
    }

    private static String url(String path, Map<String, String> query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        query.forEach(builder::queryParam);
        return builder.encode().build().toUriString();
    }
}
