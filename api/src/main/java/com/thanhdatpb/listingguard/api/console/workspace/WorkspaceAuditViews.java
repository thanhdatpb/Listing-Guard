package com.thanhdatpb.listingguard.api.console.workspace;

import com.thanhdatpb.listingguard.api.console.ConsoleDates;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditEnabled;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.AuditView;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredAudit;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredFinding;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditStore;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.State;
import com.thanhdatpb.listingguard.api.workspace.audit.FieldTexts;
import com.thanhdatpb.listingguard.api.workspace.audit.WorkspaceHighlights;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the workspace pages show of the background audit (ADR-061): the word and the shape of a listing's state, and the detail page's
 * content with its marked text and its table of findings. Every string is a message of both languages; every piece of listing text is
 * printed by the page with {@code th:text}.
 */
@Component
@AuditEnabled
public class WorkspaceAuditViews {

    /** The state of one row of the list: the css and sprite code, and the word. */
    public record StateView(String code, String label) {
    }

    /** One piece of a field; {@code tip} is the id of the tooltip of a marked piece. */
    public record SegmentView(String text, int finding, String tone, String severity, String tip) {

        public boolean marked() {
            return finding >= 0;
        }
    }

    public record FieldView(String path, String label, List<SegmentView> segments, boolean flagged, String flagSeverity, String flagLevel) {
    }

    /** One finding of the table; {@code number} is the 1-based number the marked text and the tooltip refer to. */
    public record FindingRow(int number, String tip, String level, String tone, String severity, String levelLabel, String fieldLabel,
                             String mark, String source, String rule, String serial, String tsdrUrl, boolean fresh, boolean positioned,
                             String message, String suggestion, boolean knownPlace) {
    }

    public record Detail(StateView state, String audited, boolean trademarkRan, String catalogVersion, List<FieldView> fields,
                         List<FindingRow> findings, boolean hasNew, int errors, int warnings) {
    }

    private final AuditStore store;
    private final ConsoleMessages messages;

    public WorkspaceAuditViews(AuditStore store, ConsoleMessages messages) {
        this.store = store;
        this.messages = messages;
    }

    public StateView state(State state) {
        String code = state.name().toLowerCase(Locale.ROOT);
        return new StateView(code, messages.get("workspace.audit.state." + code));
    }

    /** The state of a listing that was never audited. */
    public StateView none() {
        return new StateView("none", messages.get("workspace.check.none"));
    }

    public StateView of(AuditView view) {
        return view == null ? none() : state(view.state());
    }

    /** The detail page's audit parts, or empty when the listing has no audit yet. */
    public Optional<Detail> detail(UUID clientId, Listing listing) {
        Optional<StoredAudit> audit = store.audit(clientId, listing.id());
        if (audit.isEmpty()) {
            return Optional.empty();
        }
        List<StoredFinding> findings = store.findings(clientId, listing.id());
        Map<String, String> texts = FieldTexts.of(listing.fields());
        List<WorkspaceHighlights.Field> highlighted = WorkspaceHighlights.build(texts, findings);
        List<FieldView> fields = new ArrayList<>();
        for (WorkspaceHighlights.Field field : highlighted) {
            List<SegmentView> segments = new ArrayList<>();
            for (WorkspaceHighlights.Segment segment : field.segments()) {
                segments.add(new SegmentView(segment.text(), segment.finding(), segment.tone(),
                        segment.marked() ? WorkspaceHighlights.severity(segment.tone()) : "", segment.marked() ? tip(segment.finding()) : ""));
            }
            boolean flagged = !field.flagged().isEmpty();
            fields.add(new FieldView(field.path(), fieldLabel(field.path()), List.copyOf(segments), flagged,
                    flagged ? WorkspaceHighlights.severity(field.flagTone()) : "", flagged ? messages.get("reaudit.level." + field.flagTone()) : ""));
        }
        List<FindingRow> rows = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            StoredFinding f = findings.get(i);
            final int index = i;
            boolean inText = f.field() != null && texts.containsKey(f.field());
            boolean marked = fields.stream().anyMatch(field -> field.segments().stream().anyMatch(s -> s.finding() == index));
            rows.add(new FindingRow(i + 1, tip(i), f.level().name().toLowerCase(Locale.ROOT), f.level().code(), WorkspaceHighlights.severity(f.level().code()),
                    messages.get("reaudit.level." + f.level().code()), f.field() == null ? "" : fieldLabel(f.field()), f.mark(),
                    messages.get("workspace.audit.source." + f.source().name().toLowerCase(Locale.ROOT)), f.ruleId(), f.serial(), f.tsdrUrl(),
                    f.isNew(), marked, f.message(), f.suggestion(), inText));
        }
        StoredAudit a = audit.get();
        return Optional.of(new Detail(state(a.state()), ConsoleDates.dateTime(messages, a.auditedAt()), a.trademarkRan(), a.catalogVersion(), fields,
                List.copyOf(rows), a.newCount() > 0, a.errorCount(), a.warningCount()));
    }

    private static String tip(int findingIndex) {
        return "lg-tip-" + (findingIndex + 1);
    }

    /** {@code tags[2]} is "Tag 3" and {@code title} is "Title", in the language of the request. */
    String fieldLabel(String path) {
        int bracket = path.indexOf('[');
        String base = bracket < 0 ? path : path.substring(0, bracket);
        String label = messages.getOrDefault("workspace.audit.field." + base, base);
        if (bracket > 0 && path.endsWith("]")) {
            try {
                return label + " " + (Integer.parseInt(path.substring(bracket + 1, path.length() - 1)) + 1);
            } catch (NumberFormatException e) {
                return label;
            }
        }
        return label;
    }
}
