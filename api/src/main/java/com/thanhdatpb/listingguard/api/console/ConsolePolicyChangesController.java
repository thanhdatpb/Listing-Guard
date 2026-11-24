package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.policymonitor.PolicyImpact;
import com.thanhdatpb.listingguard.api.policymonitor.PolicyImpactReports;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Hidden
@Controller
@RequestMapping("/console/policy-changes")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsolePolicyChangesController {
    private static final String VIEW = "console/policy-changes";
    public enum Marketplace { ETSY, AMAZON }

    private final PolicyImpactReports reports;
    private final ConsoleAccountRoles roles;
    private final ConsoleMessages messages;

    public ConsolePolicyChangesController(PolicyImpactReports reports, ConsoleAccountRoles roles, ConsoleMessages messages) {
        this.reports = reports;
        this.roles = roles;
        this.messages = messages;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @ModelAttribute("impactStatuses")
    Map<PolicyImpact.Status, String> statusLabels() {
        return Map.of(PolicyImpact.Status.PROPOSED, messages.get("impact.proposed"),
                PolicyImpact.Status.NEEDS_REVIEW, messages.get("impact.pending"),
                PolicyImpact.Status.TEXT_ONLY, messages.get("impact.text.only"),
                PolicyImpact.Status.RULE_CHANGE, messages.get("impact.rule.change"),
                PolicyImpact.Status.DEFERRED, messages.get("impact.deferred"));
    }

    @ModelAttribute("impactSectionTypes")
    Map<String, String> sectionLabels() {
        return Map.of("SECTION_ADDED", messages.get("impact.added"), "SECTION_REMOVED", messages.get("impact.removed"),
                "SECTION_MOVED", messages.get("impact.moved"), "SECTION_CHANGED", messages.get("impact.changed"));
    }

    @GetMapping
    String list(@AuthenticationPrincipal ConsoleUser user, Model model,
                @RequestParam(required = false) Marketplace marketplace,
                @RequestParam(required = false) PolicyImpact.Status status,
                @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate through) {
        requireAccount(user);
        if (from != null && through != null && through.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        model.addAttribute("reports", reports.list(user.clientId(), marketplace == null ? null : marketplace.name(),
                status, from, through));
        model.addAttribute("selectedMarket", marketplace);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("from", from);
        model.addAttribute("through", through);
        model.addAttribute("markets", Marketplace.values());
        model.addAttribute("statuses", PolicyImpact.Status.values());
        return VIEW;
    }

    @GetMapping("/{id}")
    String detail(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, Model model) {
        model.addAttribute("report", report(user, id));
        model.addAttribute("manager", roles.isAdmin(user));
        return VIEW;
    }

    @PostMapping("/{id}/decision")
    String decide(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id,
                  @RequestParam PolicyImpact.Decision decision, @RequestParam(defaultValue = "false") boolean confirmed) {
        var before = report(user, id);
        if (!roles.isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (!confirmed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (before.sourceStatus() == PolicyImpact.Status.NEEDS_REVIEW && decision != PolicyImpact.Decision.DEFERRED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (!decision.name().equals(before.status().name()) && !reports.decide(user, id, decision)
                && !decision.name().equals(report(user, id).status().name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        return "redirect:/console/policy-changes/{id}";
    }

    @GetMapping("/{id}/report.csv")
    ResponseEntity<String> export(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id) {
        var report = report(user, id);
        StringBuilder csv = new StringBuilder("\uFEFFmarketplace,status,effective_date,source,old_version,new_version,change,old_path,new_path,old_hash,new_hash\r\n");
        var sections = report.evidence().sections();
        if (sections.isEmpty()) {
            csvRow(csv, report, new PolicyImpact.Section(null, null, null, null, null));
        }
        sections.forEach(section -> csvRow(csv, report, section));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"policy-impact.csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(csv.toString());
    }

    @ExceptionHandler(DataAccessException.class)
    String unavailable(HttpServletResponse response, Model model) {
        response.setStatus(503);
        noStore(response);
        model.addAttribute("unavailable", true);
        return VIEW;
    }

    private PolicyImpactReports.Report report(ConsoleUser user, UUID id) {
        requireAccount(user);
        return reports.get(user.clientId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void requireAccount(ConsoleUser user) {
        if (user == null || roles.account(user).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static void csvRow(StringBuilder csv, PolicyImpactReports.Report report, PolicyImpact.Section section) {
        String[] values = {report.marketplace(), report.status().name(), report.effectiveDate() == null ? null
                : report.effectiveDate().toString(), report.evidence().url(), report.evidence().oldVersion(),
                report.evidence().newVersion(), section.type(), section.oldPath(), section.newPath(), section.oldHash(),
                section.newHash()};
        for (int index = 0; index < values.length; index++) {
            if (index > 0) csv.append(',');
            csv.append(cell(values[index]));
        }
        csv.append("\r\n");
    }

    static String cell(String value) {
        if (value != null) {
            int first = value.codePoints().dropWhile(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || Character.isISOControl(c) || c == 0xFEFF).findFirst().orElse(-1);
            if (first >= 0 && "=+-@＝＋－＠".indexOf(first) >= 0) value = "'" + value;
        }
        return StatsCsv.cell(value);
    }
}
