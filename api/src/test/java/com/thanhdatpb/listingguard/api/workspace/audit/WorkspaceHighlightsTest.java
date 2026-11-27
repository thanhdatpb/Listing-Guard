package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredFinding;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Level;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Source;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The pieces of a field that the detail page prints (ADR-061). No database. */
class WorkspaceHighlightsTest {

    private static StoredFinding finding(String field, Integer start, Integer end, Level level, boolean fresh) {
        return new StoredFinding(1, 0, "trademark-risk", "catalog_exact", level, Source.CATALOG, field, start, end, "Zorvex", "12345678", "c1",
                null, null, "m", "s", fresh);
    }

    private static String joined(WorkspaceHighlights.Field field) {
        StringBuilder text = new StringBuilder();
        field.segments().forEach(s -> text.append(s.text()));
        return text.toString();
    }

    @Test
    void theMarkedPieceIsTheStoredTextAtTheSpanAndTheWholeFieldIsStillThere() {
        String title = "  Cool   ZORVEX!!  hoodie, Zorvex's best ";
        Map<String, String> texts = Map.of("title", title);
        int at = title.indexOf("ZORVEX");
        List<StoredFinding> findings = List.of(finding("title", at, at + 6, Level.HIGH, true));

        WorkspaceHighlights.Field field = WorkspaceHighlights.build(texts, findings).get(0);

        assertThat(joined(field)).isEqualTo(title);
        assertThat(field.segments()).filteredOn(WorkspaceHighlights.Segment::marked).extracting(WorkspaceHighlights.Segment::text)
                .containsExactly("ZORVEX");
        assertThat(field.segments()).filteredOn(WorkspaceHighlights.Segment::marked).extracting(WorkspaceHighlights.Segment::tone).containsExactly("high");
        assertThat(field.flagged()).isEmpty();
    }

    @Test
    void aFindingWithoutAPositionFlagsTheFieldAndMarksNoText() {
        Map<String, String> texts = Map.of("title", "Some title");
        WorkspaceHighlights.Field field = WorkspaceHighlights.build(texts, List.of(finding("title", null, null, Level.ERROR, false))).get(0);

        assertThat(field.segments()).hasSize(1).allMatch(s -> !s.marked());
        assertThat(field.flagged()).containsExactly(0);
        assertThat(field.flagTone()).isEqualTo("error");
    }

    @Test
    void aSpanThatDoesNotFitIsDroppedNeverGuessed() {
        Map<String, String> texts = Map.of("title", "short");
        List<StoredFinding> findings = List.of(finding("title", 3, 40, Level.HIGH, false), finding("title", 2, 2, Level.HIGH, false),
                finding("title", -1, 2, Level.HIGH, false));

        WorkspaceHighlights.Field field = WorkspaceHighlights.build(texts, findings).get(0);

        assertThat(field.segments()).hasSize(1);
        assertThat(field.flagged()).hasSize(3);
    }

    @Test
    void aSpanInsideASurrogatePairIsDropped() {
        String text = "a😀b";
        WorkspaceHighlights.Field field = WorkspaceHighlights.build(Map.of("title", text), List.of(finding("title", 2, 3, Level.HIGH, false))).get(0);

        assertThat(field.segments().stream().filter(WorkspaceHighlights.Segment::marked)).isEmpty();
        assertThat(field.flagged()).containsExactly(0);
    }

    @Test
    void theWorseFindingWinsAnOverlapAndTheOtherFlagsTheField() {
        Map<String, String> texts = Map.of("description", "a Zorvex mug for you");
        List<StoredFinding> findings = List.of(finding("description", 2, 8, Level.MEDIUM, false), finding("description", 4, 10, Level.HIGH, false));

        WorkspaceHighlights.Field field = WorkspaceHighlights.build(texts, findings).get(0);

        assertThat(field.segments()).filteredOn(WorkspaceHighlights.Segment::marked).extracting(WorkspaceHighlights.Segment::finding).containsExactly(1);
        assertThat(field.flagged()).containsExactly(0);
        assertThat(joined(field)).isEqualTo("a Zorvex mug for you");
    }

    @Test
    void severalFindingsInOneFieldAreMarkedInTheOrderOfTheText() {
        String text = "Disney and Zorvex";
        List<StoredFinding> findings = List.of(finding("title", 11, 17, Level.MEDIUM, false), finding("title", 0, 6, Level.HIGH, false));

        WorkspaceHighlights.Field field = WorkspaceHighlights.build(Map.of("title", text), findings).get(0);

        assertThat(field.segments()).extracting(WorkspaceHighlights.Segment::text).containsExactly("Disney", " and ", "Zorvex");
        assertThat(field.segments()).extracting(WorkspaceHighlights.Segment::finding).containsExactly(1, -1, 0);
    }

    @Test
    void aFindingOfAFieldThePageDoesNotPrintIsOnlyInTheTable() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("title", "t");
        List<StoredFinding> findings = List.of(finding("tags", null, null, Level.WARNING, false), finding("title", 0, 1, Level.HIGH, false));

        assertThat(WorkspaceHighlights.elsewhere(texts, findings)).containsExactly(0);
        assertThat(WorkspaceHighlights.severity("high")).isEqualTo("severe");
        assertThat(WorkspaceHighlights.severity("medium")).isEqualTo("caution");
    }
}
