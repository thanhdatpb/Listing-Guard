package com.thanhdatpb.listingguard.api.console.workspace;

import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.console.ConsoleDates;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccounts;
import com.thanhdatpb.listingguard.api.workspace.TemplateService;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.ListingFilter;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.ListingSummary;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Page;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.TemplateColumn;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTemplates;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The listing workspace's list, detail and "Accounts and templates" pages (ADR-059). The controller exists only while
 * {@code listing-guard.workspace.enabled} is true (without it every path below is a 404). Every account of the client reads everything
 * of the client; only an ADMIN creates or changes a seller account, changes the responsible person or deletes. The role is read from the
 * database at each request. A listing of another client is a 404, never a 403. Every page is {@code no-store}: it shows listing text. All
 * values are printed with {@code th:text}.
 */
@Controller
@RequestMapping("/console/workspace")
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.workspace.enabled:false}")
public class ConsoleWorkspaceController {

    /** A row of the list. */
    public record Row(UUID id, String sku, boolean child, String title, String account, String marketplace, String role, String status,
                      String statusLabel, String owner, String updated, String parentSku) {
    }

    /** A field of a listing in the order of its template. */
    public record FieldRow(String header, String value, String target, boolean passthrough) {
    }

    public record HistoryRow(long version, String kind, String status, String owner, String changedBy, String when) {
    }

    public record TemplateRow(UUID id, String kind, int version, String format, String file, String sha, String created, boolean hasFile,
                              int columns, boolean current) {
    }

    public record AccountRow(SellerAccount account, String marketplace, String formatLabel, long listings, List<TemplateRow> templates, boolean etsy) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceAccounts accounts;
    private final WorkspaceTemplates templates;
    private final WorkspaceListings listings;
    private final TemplateService templateService;
    private final ConsoleAccountRoles roles;
    private final ConsoleMessages messages;

    public ConsoleWorkspaceController(WorkspaceAccess access, WorkspaceAccounts accounts, WorkspaceTemplates templates,
                                      WorkspaceListings listings, TemplateService templateService, ConsoleAccountRoles roles,
                                      ConsoleMessages messages) {
        this.access = access;
        this.accounts = accounts;
        this.templates = templates;
        this.listings = listings;
        this.templateService = templateService;
        this.roles = roles;
        this.messages = messages;
    }

    private Actor actor(ConsoleUser user) {
        return access.actor(user).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    private Actor admin(ConsoleUser user) {
        Actor actor = actor(user);
        if (!access.canManage(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return actor;
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

    // ------------------------------------------------------------------ list

    @GetMapping
    public String list(@AuthenticationPrincipal ConsoleUser user, @RequestParam Map<String, String> params, Model model,
                       HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        String owner = params.getOrDefault("owner", "");
        UUID ownerId = "me".equals(owner) ? actor.accountId() : uuid(owner);
        ListingFilter filter = new ListingFilter(uuid(params.get("account")),
                WorkspaceTypes.parse(Marketplace.class, params.get("marketplace")).orElse(null),
                WorkspaceTypes.parse(ListingStatus.class, params.get("status")).orElse(null), ownerId, "none".equals(owner),
                params.getOrDefault("q", "").strip());
        int pageNumber = 0;
        try {
            pageNumber = Math.max(0, Integer.parseInt(params.getOrDefault("page", "0")));
        } catch (NumberFormatException ignored) {
            // an unreadable page number is the first page
        }
        Page<ListingSummary> page = listings.list(actor.clientId(), filter, pageNumber);
        Map<UUID, String> emails = roles.emails(actor.clientId());
        List<Row> rows = new ArrayList<>();
        for (ListingSummary s : page.items()) {
            rows.add(new Row(s.id(), s.sku(), s.parentSku() != null, s.title(), s.accountName(), labels.marketplace(s.marketplace()),
                    labels.role(s.role()), s.status().name().toLowerCase(java.util.Locale.ROOT), labels.status(s.status()),
                    s.ownerAccountId() == null ? null : emails.getOrDefault(s.ownerAccountId(), ""), ConsoleDates.dateTime(messages, s.updatedAt()),
                    s.parentSku()));
        }
        List<SellerAccount> sellerAccounts = accounts.list(actor.clientId());
        List<String[]> statuses = new ArrayList<>();
        for (ListingStatus status : ListingStatus.values()) {
            statuses.add(new String[] {status.name(), labels.status(status)});
        }
        model.addAttribute("rows", rows);
        model.addAttribute("page", page);
        model.addAttribute("sellerAccounts", sellerAccounts);
        model.addAttribute("owners", emails);
        model.addAttribute("statuses", statuses);
        model.addAttribute("filterAccount", filter.accountId() == null ? "" : filter.accountId().toString());
        model.addAttribute("filterMarketplace", filter.marketplace() == null ? "" : filter.marketplace().name());
        model.addAttribute("filterStatus", filter.status() == null ? "" : filter.status().name());
        model.addAttribute("filterOwner", owner);
        model.addAttribute("filterQuery", filter.query());
        model.addAttribute("filtered", !filter.equals(ListingFilter.none()) && (filter.accountId() != null || filter.marketplace() != null
                || filter.status() != null || filter.owner() != null || filter.noOwner() || !filter.query().isEmpty()));
        model.addAttribute("admin", actor.admin());
        model.addAttribute("hasAccounts", !sellerAccounts.isEmpty());
        model.addAttribute("first", page.total() == 0 ? 0 : (long) page.page() * page.pageSize() + 1);
        model.addAttribute("last", Math.min(page.total(), (long) (page.page() + 1) * page.pageSize()));
        return "console/workspace";
    }

    // ------------------------------------------------------------------ detail

    @GetMapping("/listings/{id}")
    public String detail(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, Model model, HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        Listing listing = listings.find(actor.clientId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        Template template = templates.find(actor.clientId(), listing.templateId()).orElseThrow();
        SellerAccount account = accounts.find(actor.clientId(), listing.accountId()).orElseThrow();
        Map<UUID, String> emails = roles.emails(actor.clientId());
        List<FieldRow> fields = new ArrayList<>();
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (TemplateColumn column : template.columns()) {
            String value = listing.data().get(column.key());
            shown.add(column.key());
            if (value != null) {
                String target = targetOf(template, column.key());
                fields.add(new FieldRow(column.header().isBlank() ? column.key() : column.header(), value, target == null ? "" : labels.target(target),
                        target == null));
            }
        }
        listing.data().forEach((key, value) -> {
            if (!shown.contains(key)) {
                String target = template.kind() == TemplateKind.ETSY_FIELDS && key.contains("~") ? key.substring(0, key.indexOf('~')) : null;
                fields.add(new FieldRow(key, value, target == null ? "" : labels.target(target), target == null));
            }
        });
        List<HistoryRow> history = new ArrayList<>();
        listings.history(actor.clientId(), id).forEach(h -> history.add(new HistoryRow(h.version(), messages.get("workspace.change." + h.kind().name().toLowerCase(java.util.Locale.ROOT)),
                labels.status(h.status()), h.ownerAccountId() == null ? null : emails.get(h.ownerAccountId()),
                h.changedBy() == null ? null : emails.get(h.changedBy()), ConsoleDates.dateTime(messages, h.changedAt()))));
        model.addAttribute("listing", listing);
        model.addAttribute("account", account);
        model.addAttribute("marketplaceLabel", labels.marketplace(account.marketplace()));
        model.addAttribute("kindLabel", labels.kind(template.kind()));
        model.addAttribute("template", template);
        model.addAttribute("statusLabel", labels.status(listing.status()));
        model.addAttribute("roleLabel", labels.role(listing.role()));
        model.addAttribute("sourceLabel", messages.get("workspace.source." + listing.source().name().toLowerCase(java.util.Locale.ROOT)));
        model.addAttribute("ownerEmail", listing.ownerAccountId() == null ? null : emails.get(listing.ownerAccountId()));
        model.addAttribute("owners", emails);
        model.addAttribute("fields", fields);
        model.addAttribute("history", history);
        model.addAttribute("created", ConsoleDates.dateTime(messages, listing.createdAt()));
        model.addAttribute("updated", ConsoleDates.dateTime(messages, listing.updatedAt()));
        model.addAttribute("children", listing.sku() == null ? List.of() : listings.children(actor.clientId(), listing.accountId(), listing.sku()));
        model.addAttribute("parent", listing.parentSku() == null ? null : listings.existing(actor.clientId(), listing.accountId(), List.of(listing.parentSku())).get(listing.parentSku()));
        model.addAttribute("clonedFrom", listing.clonedFrom() == null ? null : listings.find(actor.clientId(), listing.clonedFrom()).orElse(null));
        model.addAttribute("customization", listings.customization(actor.clientId(), listing.accountId(), listing.sku()).orElse(null));
        model.addAttribute("admin", actor.admin());
        model.addAttribute("canEdit", access.canEdit(actor, listing.ownerAccountId()));
        return "console/workspace-detail";
    }

    private static String targetOf(Template template, String key) {
        return template.mapping().targets().get(key);
    }

    @PostMapping("/listings/{id}/owner")
    public String changeOwner(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, @RequestParam(defaultValue = "") String owner,
                              @RequestParam long version, RedirectAttributes redirect) {
        Actor actor = admin(user);
        UUID ownerId = uuid(owner);
        if (ownerId != null && !roles.emails(actor.clientId()).containsKey(ownerId)) {
            redirect.addFlashAttribute("error", new WorkspaceLabels(messages).error("owner.unknown"));
            return "redirect:/console/workspace/listings/" + id;
        }
        if (listings.find(actor.clientId(), id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (listings.changeOwner(actor.clientId(), id, ownerId, version, actor.accountId())) {
            redirect.addFlashAttribute("notice", messages.get("workspace.detail.owner.saved"));
        } else {
            redirect.addFlashAttribute("error", messages.get("workspace.detail.conflict"));
        }
        return "redirect:/console/workspace/listings/" + id;
    }

    @PostMapping("/listings/{id}/delete")
    public String delete(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, @RequestParam long version,
                         RedirectAttributes redirect) {
        Actor actor = admin(user);
        if (listings.find(actor.clientId(), id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (listings.softDelete(actor.clientId(), id, version, actor.accountId())) {
            redirect.addFlashAttribute("notice", messages.get("workspace.detail.deleted"));
            return "redirect:/console/workspace";
        }
        redirect.addFlashAttribute("error", messages.get("workspace.detail.conflict"));
        return "redirect:/console/workspace/listings/" + id;
    }

    // ------------------------------------------------------------------ accounts and templates

    @GetMapping("/accounts")
    public String accountsPage(@AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        List<AccountRow> rows = new ArrayList<>();
        for (SellerAccount account : accounts.list(actor.clientId())) {
            List<TemplateRow> versions = new ArrayList<>();
            Map<TemplateKind, Integer> newest = new LinkedHashMap<>();
            List<Template> all = templates.list(actor.clientId(), account.id());
            for (Template t : all) {
                newest.merge(t.kind(), t.version(), Math::max);
            }
            for (Template t : all) {
                versions.add(new TemplateRow(t.id(), labels.kind(t.kind()), t.version(), t.sourceFormat(), t.sourceFileName(),
                        t.fileSha256() == null ? null : t.fileSha256().substring(0, 12), ConsoleDates.dateTime(messages, t.createdAt()),
                        t.hasFile(), t.columns().size(), t.version() == newest.get(t.kind())));
            }
            rows.add(new AccountRow(account, labels.marketplace(account.marketplace()), labels.format(account.exportFormat().name()), listings.count(actor.clientId(), account.id()),
                    versions, account.marketplace() == Marketplace.ETSY));
        }
        model.addAttribute("rows", rows);
        model.addAttribute("admin", actor.admin());
        model.addAttribute("marketplaces", List.of(new String[] {"AMAZON", labels.marketplace(Marketplace.AMAZON)},
                new String[] {"ETSY", labels.marketplace(Marketplace.ETSY)}));
        model.addAttribute("formats", List.of(new String[] {"XLSM", labels.format("XLSM")}, new String[] {"TXT", labels.format("TXT")}));
        return "console/workspace-accounts";
    }

    @PostMapping("/accounts")
    public String createAccount(@AuthenticationPrincipal ConsoleUser user, @RequestParam String name, @RequestParam String marketplace,
                                @RequestParam(defaultValue = "") String brand, @RequestParam(defaultValue = "XLSM") String exportFormat,
                                RedirectAttributes redirect) {
        Actor actor = admin(user);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        try {
            Marketplace m = WorkspaceTypes.parse(Marketplace.class, marketplace).orElseThrow(() -> new WorkspaceException("marketplace.unknown"));
            ExportFormat format = WorkspaceTypes.parse(ExportFormat.class, exportFormat).orElse(ExportFormat.XLSM);
            SellerAccount created = accounts.create(actor.clientId(), m, name, brand, format);
            if (m == Marketplace.ETSY) {
                templateService.ensureEtsy(actor.clientId(), created.id());
            }
            redirect.addFlashAttribute("notice", messages.get("workspace.accounts.created"));
        } catch (WorkspaceException e) {
            redirect.addFlashAttribute("error", labels.error(e.code()));
        }
        return "redirect:/console/workspace/accounts";
    }

    @PostMapping("/accounts/{id}")
    public String updateAccount(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, @RequestParam String name,
                                @RequestParam(defaultValue = "") String brand, @RequestParam(defaultValue = "XLSM") String exportFormat,
                                @RequestParam(defaultValue = "") String enabled, RedirectAttributes redirect) {
        Actor actor = admin(user);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        try {
            ExportFormat format = WorkspaceTypes.parse(ExportFormat.class, exportFormat).orElse(ExportFormat.XLSM);
            if (!accounts.update(actor.clientId(), id, name, brand, format, "on".equals(enabled) || "true".equals(enabled))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            redirect.addFlashAttribute("notice", messages.get("workspace.accounts.saved"));
        } catch (WorkspaceException e) {
            redirect.addFlashAttribute("error", labels.error(e.code()));
        }
        return "redirect:/console/workspace/accounts";
    }

    /** The original file of a template, byte for byte (an ADMIN's download: it may hold the seller's own data). */
    @GetMapping("/templates/{id}/file")
    public ResponseEntity<byte[]> templateFile(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id) {
        Actor actor = admin(user);
        Template template = templates.find(actor.clientId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        byte[] bytes = templates.fileBytes(actor.clientId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String extension = "XLSM".equals(template.sourceFormat()) ? "xlsm" : "xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("template-v" + template.version() + "." + extension, StandardCharsets.UTF_8).build().toString())
                .body(bytes);
    }
}
