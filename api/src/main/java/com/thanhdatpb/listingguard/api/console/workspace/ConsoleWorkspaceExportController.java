package com.thanhdatpb.listingguard.api.console.workspace;

import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.console.ConsoleDates;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccounts;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceExportLog;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceExports;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceExports.Kind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceExports.Medium;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.ListingFilter;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetRejected;
import com.thanhdatpb.listingguard.workspace.sheets.TextEncoding;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The export of a seller account's listings (ADR-063): the form, the confirmation, the file or the text to paste, and the two buttons that follow
 * an export ("posted" for Etsy, "on sale" for what was exported). The confirmation writes nothing; the last button makes the file or the text and
 * moves the listings in one transaction. A file is never kept: its bytes go back in the answer. Every id of another client is a 404, the pages are
 * {@code no-store}, and every value is printed with {@code th:text}. No text is written here (ADR-028).
 */
@Controller
@RequestMapping("/console/workspace")
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.workspace.enabled:false}")
public class ConsoleWorkspaceExportController {

    public record AccountOption(UUID id, String name, String format) {
    }

    public record GroupRow(UUID templateId, String version, String kind, String source, int listings, int held, int untrademarked, int opened,
                           int orphans, int replaced, String values, int valueRules, int valueUnresolved, int valueExtension, boolean valuesComplete) {
    }

    public record WarningRow(String version, String header, int listings, String examples) {
    }

    public record HeldRow(UUID id, String sku, String title, String status, String why, List<String> blocks) {
    }

    public record LogRow(String when, String format, String version, String kind, int listings, int blocked, int opened, int untrademarked, int warnings,
                         int replaced, String by) {
    }

    public record Choice(String code, String label, boolean disabled) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceAccounts accounts;
    private final WorkspaceListings listings;
    private final WorkspaceExports exports;
    private final WorkspaceExportLog exportLog;
    private final ConsoleAccountRoles roles;
    private final ConsoleMessages messages;

    public ConsoleWorkspaceExportController(WorkspaceAccess access, WorkspaceAccounts accounts, WorkspaceListings listings, WorkspaceExports exports,
                                            WorkspaceExportLog exportLog, ConsoleAccountRoles roles,
                                            ConsoleMessages messages) {
        this.access = access;
        this.accounts = accounts;
        this.listings = listings;
        this.exports = exports;
        this.exportLog = exportLog;
        this.roles = roles;
        this.messages = messages;
    }

    private Actor actor(ConsoleUser user) {
        return access.actor(user).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
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

    private SellerAccount account(Actor actor, UUID id) {
        return accounts.find(actor.clientId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // ------------------------------------------------------------------ the form

    /** The first step: the account and the filter. With {@code account} the form is for that account. */
    @GetMapping("/export")
    public String form(@AuthenticationPrincipal ConsoleUser user, @RequestParam(required = false) String account, Model model,
                       HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        UUID accountId = uuid(account);
        SellerAccount selected = accountId == null ? null : account(actor, accountId);
        fillForm(model, actor, selected, ListingStatus.READY_TO_EXPORT.name(), "", "", null);
        return "console/workspace-export";
    }

    private void fillForm(Model model, Actor actor, SellerAccount selected, String status, String owner, String query, String error) {
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        List<AccountOption> options = new ArrayList<>();
        for (SellerAccount a : accounts.list(actor.clientId())) {
            if (a.marketplace() == Marketplace.AMAZON && a.enabled()) {
                options.add(new AccountOption(a.id(), a.name(), labels.format(a.exportFormat().name())));
            }
        }
        model.addAttribute("accountOptions", options);
        model.addAttribute("selected", selected);
        model.addAttribute("filterStatus", status);
        model.addAttribute("filterOwner", owner);
        model.addAttribute("filterQuery", query);
        model.addAttribute("owners", roles.emails(actor.clientId()));
        model.addAttribute("statuses", List.of(ListingStatus.values()).stream().map(s -> new Choice(s.name(), labels.status(s), false)).toList());
        model.addAttribute("maxListings", exports.maxListings());
        model.addAttribute("error", error != null ? error : model.getAttribute("error"));
        model.addAttribute("preview", null);
        if (selected != null) {
            model.addAttribute("log", logRows(actor, selected, labels));
        }
    }

    private List<LogRow> logRows(Actor actor, SellerAccount account, WorkspaceLabels labels) {
        Map<UUID, String> emails = roles.emails(actor.clientId());
        List<LogRow> rows = new ArrayList<>();
        for (WorkspaceExportLog.Entry e : exportLog.recent(actor.clientId(), account.id(), 10)) {
            rows.add(new LogRow(ConsoleDates.dateTime(messages, e.exportedAt()), messages.get("workspace.export.format." + e.format().name().toLowerCase(Locale.ROOT)),
                    e.templateVersion() == null ? "" : "v" + e.templateVersion(), labels.kind(e.templateKind()), e.listings(), e.blocked(), e.opened(),
                    e.untrademarked(), e.warnings(), e.replacedCells(), e.exportedBy() == null ? "" : emails.getOrDefault(e.exportedBy(), "")));
        }
        return rows;
    }

    // ------------------------------------------------------------------ the confirmation, the file and the text

    /**
     * {@code step=preview} (the default) shows what would be exported; {@code step=export} makes it. The listings are the {@code listing} parameters
     * (the selection of the list), or, with an {@code account} and no listing, what the filter ({@code status}, {@code owner}, {@code q}) matches.
     */
    @PostMapping("/export")
    public Object export(@AuthenticationPrincipal ConsoleUser user, @RequestParam MultiValueMap<String, String> params, Model model,
                         HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        List<UUID> ids = new ArrayList<>();
        params.getOrDefault("listing", List.of()).forEach(text -> {
            UUID id = uuid(text);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        });
        UUID accountId = uuid(params.getFirst("account"));
        String status = params.getFirst("status") == null ? ListingStatus.READY_TO_EXPORT.name() : params.getFirst("status");
        String owner = params.getFirst("owner") == null ? "" : params.getFirst("owner");
        String query = params.getFirst("q") == null ? "" : params.getFirst("q");
        if (accountId == null && !ids.isEmpty()) {
            // a selection of the list: its account is the account of its listings, and a selection of several is refused
            Map<UUID, Listing> found = listings.findMany(actor.clientId(), ids);
            List<UUID> accountsOf = found.values().stream().map(Listing::accountId).distinct().toList();
            if (accountsOf.size() > 1) {
                fillForm(model, actor, null, status, owner, query, labels.error("export.accounts"));
                response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
                return "console/workspace-export";
            }
            accountId = accountsOf.isEmpty() ? null : accountsOf.get(0);
        }
        if (accountId == null) {
            fillForm(model, actor, null, status, owner, query, ids.isEmpty() ? null : labels.error("account.unknown"));
            return "console/workspace-export";
        }
        SellerAccount account = account(actor, accountId);
        boolean explicit = !ids.isEmpty();
        if (!explicit) {
            ListingStatus wanted = WorkspaceTypes.parse(ListingStatus.class, status).orElse(null);
            UUID ownerId = uuid(owner);
            ids.addAll(exports.idsOf(actor, accountId, new ListingFilter(null, null, wanted, ownerId, "none".equals(owner), query)));
        }
        Kind kind = "CUSTOM".equalsIgnoreCase(params.getFirst("kind")) ? Kind.CUSTOM : Kind.BULK;
        Medium medium = WorkspaceTypes.parse(Medium.class, params.getFirst("medium")).orElse(null);
        TextEncoding encoding = TextEncoding.parse(params.getFirst("encoding")).orElse(null);
        UUID templateId = uuid(params.getFirst("templateId"));
        boolean withHeader = params.containsKey("withHeader");
        boolean checkValues = params.containsKey("checkValues");
        boolean doExport = "export".equals(params.getFirst("step"));
        Integer confirm = null;
        try {
            confirm = params.getFirst("confirm") == null ? null : Integer.valueOf(params.getFirst("confirm").strip());
        } catch (NumberFormatException e) {
            // an unreadable count never matches: the export stops and the confirmation is shown again
        }
        WorkspaceExports.Request request = new WorkspaceExports.Request(accountId, kind, medium, encoding, templateId, ids, checkValues, withHeader,
                doExport ? (confirm == null ? -1 : confirm) : null);
        String error = null;
        if (doExport) {
            try {
                WorkspaceExports.Result result = exports.export(actor, request);
                return answer(model, labels, account, request, result, response);
            } catch (WorkspaceException e) {
                error = exportError(labels, e);
                response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            }
        }
        preview(model, actor, labels, account, request, ids, status, owner, query, error);
        return "console/workspace-export";
    }

    private String exportError(WorkspaceLabels labels, WorkspaceException e) {
        if (e.code().startsWith("export.sheet.")) {
            try {
                return labels.sheetProblem(SheetRejected.Reason.valueOf(e.code().substring("export.sheet.".length()).toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                return labels.error("export.blocked");
            }
        }
        return labels.error(e.code());
    }

    /** The file as a download, or the text to paste as a page. */
    private Object answer(Model model, WorkspaceLabels labels, SellerAccount account, WorkspaceExports.Request request, WorkspaceExports.Result result,
                          HttpServletResponse response) {
        if (result.clipboard() != null) {
            model.addAttribute("account", account);
            model.addAttribute("text", result.clipboard());
            model.addAttribute("exported", result.exported());
            model.addAttribute("held", result.held());
            model.addAttribute("untrademarked", result.untrademarked());
            model.addAttribute("opened", result.opened());
            model.addAttribute("kindLabel", labels.kind(request.kind() == Kind.BULK ? TemplateKind.AMAZON_BULK
                    : TemplateKind.AMAZON_CUSTOM));
            model.addAttribute("markedExported", request.kind() == Kind.BULK);
            return "console/workspace-export-paste";
        }
        // nothing is kept: the bytes go in the answer, as an attachment the browser saves
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(result.downloadName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(result.bytes());
    }

    private void preview(Model model, Actor actor, WorkspaceLabels labels, SellerAccount account, WorkspaceExports.Request request, List<UUID> ids,
                         String status, String owner, String query, String error) {
        WorkspaceExports.Preview preview = exports.preview(actor, request);
        Map<UUID, String> versionLabels = new LinkedHashMap<>();
        List<GroupRow> groups = new ArrayList<>();
        List<WarningRow> warnings = new ArrayList<>();
        for (WorkspaceExports.Group g : preview.groups()) {
            String version = "v" + g.template().version();
            versionLabels.put(g.template().id(), version + " (" + messages.get("workspace.export.group.listings", g.listings()) + ")");
            var v = g.validation();
            groups.add(new GroupRow(g.template().id(), version, labels.kind(g.template().kind()), g.template().sourceFormat(), g.listings(), g.held(),
                    g.untrademarked(), g.opened(), g.orphanChildren(), g.replacedCells(), messages.get("workspace.export.values." + v.state().toLowerCase(Locale.ROOT)),
                    v.listRules(), v.unresolved(), v.extensionRules(), v.complete()));
            v.warnings().forEach(w -> warnings.add(new WarningRow(version, w.header(), w.listings(), String.join(", ", w.values()))));
        }
        List<HeldRow> held = preview.held().stream().map(h -> new HeldRow(h.id(), h.sku(), h.title(), labels.status(h.status()),
                messages.get("workspace.export.held." + h.why()), h.blocks().stream().map(labels::block).toList())).toList();
        List<Choice> media = new ArrayList<>();
        boolean noExcel = "nofile".equals(preview.excelProblem());
        media.add(new Choice("EXCEL", messages.get("workspace.export.medium.excel"), noExcel));
        media.add(new Choice("TXT", messages.get("workspace.export.medium.txt"), false));
        media.add(new Choice("CLIPBOARD", messages.get("workspace.export.medium.clipboard"), false));
        List<Choice> encodings = new ArrayList<>();
        for (TextEncoding e : TextEncoding.values()) {
            encodings.add(new Choice(e.code(), messages.get("workspace.export.encoding." + e.name().toLowerCase(Locale.ROOT)), false));
        }
        fillForm(model, actor, account, status, owner, query, error);
        model.addAttribute("preview", preview);
        model.addAttribute("request", request);
        model.addAttribute("ids", ids);
        model.addAttribute("groups", groups);
        model.addAttribute("warnings", warnings);
        model.addAttribute("held", held);
        model.addAttribute("problems", preview.problems().stream().map(p -> messages.get("workspace.export.problem." + p)).toList());
        model.addAttribute("media", media);
        model.addAttribute("medium", preview.medium().name());
        model.addAttribute("encodings", encodings);
        model.addAttribute("encoding", preview.encoding().code());
        model.addAttribute("versions", versionLabels);
        model.addAttribute("kind", request.kind().name());
        model.addAttribute("kindLabel", labels.kind(request.kind() == Kind.BULK ? TemplateKind.AMAZON_BULK
                : TemplateKind.AMAZON_CUSTOM));
        model.addAttribute("selectedTemplate", request.templateId() == null ? "" : request.templateId().toString());
        model.addAttribute("formatLabel", labels.format(account.exportFormat().name()));
        model.addAttribute("gateOn", preview.gateActive());
        model.addAttribute("auditOn", preview.auditActive());
    }

    // ------------------------------------------------------------------ after the export

    /** "Posted": an Etsy listing that was posted by hand goes live through the same gate as an export. */
    @PostMapping("/listings/{id}/posted")
    public String posted(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, RedirectAttributes redirect) {
        Actor actor = actor(user);
        listings.find(actor.clientId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        WorkspaceExports.PostOutcome outcome = exports.markPosted(actor, id);
        switch (outcome.result()) {
            case UNKNOWN -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            case FORBIDDEN -> throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            case POSTED, POSTED_OPENED -> redirect.addFlashAttribute("notice", messages.get("workspace.post." + outcome.result().name().toLowerCase(Locale.ROOT)));
            default -> redirect.addFlashAttribute("error", messages.get("workspace.post." + outcome.result().name().toLowerCase(Locale.ROOT)));
        }
        return "redirect:/console/workspace/listings/" + id;
    }

    /** "On sale": a listing that was exported is now on the marketplace. */
    @PostMapping("/listings/{id}/live")
    public String live(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, RedirectAttributes redirect) {
        Actor actor = actor(user);
        listings.find(actor.clientId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            boolean done = exports.markLive(actor, id);
            redirect.addFlashAttribute(done ? "notice" : "error", messages.get(done ? "workspace.live.done" : "workspace.live.no"));
        } catch (WorkspaceException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return "redirect:/console/workspace/listings/" + id;
    }
}
