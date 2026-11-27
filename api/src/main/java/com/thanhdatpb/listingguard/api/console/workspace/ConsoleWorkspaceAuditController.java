package com.thanhdatpb.listingguard.api.console.workspace;

import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.console.ConsoleDates;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccounts;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.audit.AlertService;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditEnabled;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.AccountRun;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.Run;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StateCounts;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditStore;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Reason;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.RunStatus;
import com.thanhdatpb.listingguard.api.workspace.audit.WorkspaceAudit;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The background audit's page of the workspace (ADR-061): progress by seller account (x of y, refreshed by htmx while a run works), the
 * history of runs, the button that audits an account again (ADMIN), a person's own switch for the e-mail, and the mark that says a listing's
 * new risks were seen. The controller exists only while the audit is on; every path is a 404 otherwise. Every client's data is read with the
 * client of the signed-in account; an account or a listing of another client is a 404. Every page is {@code no-store}.
 */
@Controller
@RequestMapping("/console/workspace/audit")
@AuditEnabled
public class ConsoleWorkspaceAuditController {

    /** One line of progress or history. {@code percent} is for the bar, {@code running} for the page that keeps refreshing. */
    public record RunRow(UUID accountId, String account, String marketplace, String reason, String status, String statusCode, int total,
                         int processed, int percent, boolean running, int newRisks, int newRiskListings, String catalog, String watchlist,
                         String started, String finished, String note) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceAccounts accounts;
    private final WorkspaceListings listings;
    private final AuditStore store;
    private final WorkspaceAudit audit;
    private final ConsoleAccountRoles roles;
    private final ConsoleMessages messages;
    private final org.springframework.beans.factory.ObjectProvider<AlertService> alerts;

    public ConsoleWorkspaceAuditController(WorkspaceAccess access, WorkspaceAccounts accounts, WorkspaceListings listings, AuditStore store,
                                           WorkspaceAudit audit, ConsoleAccountRoles roles, ConsoleMessages messages,
                                           org.springframework.beans.factory.ObjectProvider<AlertService> alerts) {
        this.access = access;
        this.accounts = accounts;
        this.listings = listings;
        this.store = store;
        this.audit = audit;
        this.roles = roles;
        this.messages = messages;
        this.alerts = alerts;
    }

    private Actor actor(ConsoleUser user) {
        return access.actor(user).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping
    public String page(@AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        List<SellerAccount> sellerAccounts = accounts.list(actor.clientId());
        List<RunRow> progress = progress(actor, sellerAccounts);
        StateCounts counts = store.counts(actor.clientId(), null);
        List<RunRow> history = new ArrayList<>();
        store.history(actor.clientId(), 20).forEach(run -> history.add(row(run, runStatusFor(run.run()))));
        model.addAttribute("progress", progress);
        model.addAttribute("running", progress.stream().anyMatch(RunRow::running));
        model.addAttribute("history", history);
        model.addAttribute("counts", counts);
        model.addAttribute("hasAccounts", !sellerAccounts.isEmpty());
        model.addAttribute("admin", actor.admin());
        model.addAttribute("alertsOn", alerts.getIfAvailable() != null);
        model.addAttribute("emailEnabled", store.emailEnabled(actor.clientId(), actor.accountId()));
        model.addAttribute("lastAudited", store.lastAuditedAt(actor.clientId()) == null ? null
                : ConsoleDates.dateTime(messages, store.lastAuditedAt(actor.clientId())));
        return "console/workspace-audit";
    }

    /** The progress block alone, for htmx: it asks again every few seconds while a run works and stops when none does. */
    @GetMapping("/progress")
    public String progress(@AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        List<RunRow> progress = progress(actor, accounts.list(actor.clientId()));
        model.addAttribute("progress", progress);
        model.addAttribute("running", progress.stream().anyMatch(RunRow::running));
        model.addAttribute("admin", actor.admin());
        return "console/workspace-fragments :: progress";
    }

    /** ADMIN: audit a seller account again. A listing whose text and analysis did not change is skipped by the run. */
    @PostMapping("/accounts/{id}")
    public String run(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id,
                      @RequestHeader(value = "HX-Request", required = false) String htmx, Model model, RedirectAttributes redirect,
                      HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        if (!access.canManage(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (audit.request(actor.clientId(), id, Reason.MANUAL, null, actor.accountId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (htmx != null) {
            List<RunRow> progress = progress(actor, accounts.list(actor.clientId()));
            model.addAttribute("progress", progress);
            model.addAttribute("running", true);
            model.addAttribute("admin", true);
            return "console/workspace-fragments :: progress";
        }
        redirect.addFlashAttribute("notice", messages.get("workspace.audit.queued"));
        return "redirect:/console/workspace/audit";
    }

    /** A person's own switch for the alert e-mail; nobody else's. */
    @PostMapping("/email")
    public String email(@AuthenticationPrincipal ConsoleUser user, @RequestParam(defaultValue = "") String enabled, RedirectAttributes redirect) {
        Actor actor = actor(user);
        store.setEmailEnabled(actor.clientId(), actor.accountId(), "on".equals(enabled) || "true".equals(enabled));
        redirect.addFlashAttribute("notice", messages.get("workspace.audit.email.saved"));
        return "redirect:/console/workspace/audit";
    }

    /** The person has seen the new risks of a listing they may change. */
    @PostMapping("/listings/{id}/ack")
    public String acknowledge(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, RedirectAttributes redirect) {
        Actor actor = actor(user);
        Listing listing = listings.find(actor.clientId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!access.canEdit(actor, listing.ownerAccountId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (store.acknowledge(actor.clientId(), id)) {
            redirect.addFlashAttribute("notice", messages.get("workspace.audit.ack.saved"));
        }
        return "redirect:/console/workspace/listings/" + id;
    }

    // ------------------------------------------------------------------ rows

    private List<RunRow> progress(Actor actor, List<SellerAccount> sellerAccounts) {
        Map<UUID, AccountRun> latest = new java.util.LinkedHashMap<>();
        store.latestRuns(actor.clientId()).forEach(run -> latest.put(run.accountId(), run));
        List<RunRow> rows = new ArrayList<>();
        for (SellerAccount account : sellerAccounts) {
            AccountRun run = latest.get(account.id());
            rows.add(run == null ? new RunRow(account.id(), account.name(), marketplace(account.marketplace().name()), "", messages.get("workspace.audit.run.never"),
                    "never", 0, 0, 0, false, 0, 0, "", "", "", "", "") : row(run, runStatusFor(run.run())));
        }
        return rows;
    }

    private static RunStatus runStatusFor(Run run) {
        return run.status();
    }

    private RunRow row(AccountRun accountRun, RunStatus status) {
        Run run = accountRun.run();
        int processed = Math.min(run.total(), run.processed());
        int percent = run.total() == 0 ? (status == RunStatus.DONE ? 100 : 0) : (int) (100L * processed / run.total());
        String code = status.name().toLowerCase(Locale.ROOT);
        return new RunRow(accountRun.accountId(), accountRun.accountName(), marketplace(accountRun.marketplace()),
                messages.get("workspace.audit.reason." + run.reason().name().toLowerCase(Locale.ROOT)), messages.get("workspace.audit.run." + code), code,
                run.total(), processed, percent, status == RunStatus.QUEUED || status == RunStatus.RUNNING, run.newRisks(), run.newRiskListings(),
                run.catalogVersion() == null ? "" : run.catalogVersion(), run.watchlistVersion() == null ? "" : run.watchlistVersion().substring(0, Math.min(12, run.watchlistVersion().length())),
                run.startedAt() == null ? "" : ConsoleDates.dateTime(messages, run.startedAt()),
                run.finishedAt() == null ? "" : ConsoleDates.dateTime(messages, run.finishedAt()),
                run.noteCode() == null ? "" : messages.get("workspace.audit.note." + run.noteCode()));
    }

    private String marketplace(String code) {
        return messages.get("workspace.marketplace." + code.toLowerCase(Locale.ROOT));
    }
}
