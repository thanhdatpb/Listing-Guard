package com.thanhdatpb.listingguard.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyChunkerTest {

    private static final String TITLE = "Doc";

    private final PolicyChunker chunker = new PolicyChunker(new PolicyChunker.Config(200, 50));

    private static ParsedPolicy.Section section(String anchor, List<String> headings, String... units) {
        return new ParsedPolicy.Section(headings, anchor, Arrays.asList(units));
    }

    private static ParsedPolicy policy(ParsedPolicy.Section... sections) {
        return new ParsedPolicy(Arrays.asList(sections), null);
    }

    private static String text(int length, char fill) {
        return String.valueOf(fill).repeat(length);
    }

    @Test
    void oneChunkPerSectionFollowingTheStructure() {
        List<PolicyChunk> chunks = chunker.chunk(TITLE, policy(
                section(null, List.of(), text(60, 'a')),
                section("q1", List.of("A"), text(60, 'b')),
                section("q1", List.of("A", "A1"), text(60, 'c'))));

        assertThat(chunks).extracting(PolicyChunk::sectionPath).containsExactly("Doc", "Doc > A", "Doc > A > A1");
        assertThat(chunks).extracting(PolicyChunk::ordinal).containsExactly(0, 1, 2);
        assertThat(chunks.get(1).anchor()).isEqualTo("q1");
        assertThat(chunks.get(2).headingPath()).containsExactly("A", "A1");
        assertThat(chunks.get(2).metadata()).containsEntry("heading_path", List.of("A", "A1"));
        assertThat(chunks.get(2).content()).isEqualTo(text(60, 'c'));
    }

    @Test
    void unitsOfASectionAreJoinedByBlankLines() {
        List<PolicyChunk> chunks = chunker.chunk(TITLE, policy(section(null, List.of("A"), text(30, 'a'), text(30, 'b'))));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo(text(30, 'a') + "\n\n" + text(30, 'b'));
    }

    @Test
    void aLongSectionIsSplitBetweenParagraphsAndEveryPartKeepsTheHeadings() {
        // 120 + 2 + 120 > 200, so each paragraph ends up in a part of its own
        String p1 = text(120, 'a');
        String p2 = text(120, 'b');
        String p3 = text(120, 'c');
        String p4 = text(120, 'd');

        List<PolicyChunk> chunks = chunker.chunk(TITLE, policy(section("q1", List.of("Big", "Section"), p1, p2, p3, p4)));

        assertThat(chunks).hasSize(4);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.sectionPath()).isEqualTo("Doc > Big > Section");
            assertThat(chunk.anchor()).isEqualTo("q1");
            assertThat(chunk.content().length()).isLessThanOrEqualTo(200);
            assertThat(chunk.metadata()).containsEntry("parts", 4);
        });
        assertThat(chunks).extracting(chunk -> chunk.metadata().get("part")).containsExactly(1, 2, 3, 4);
        assertThat(chunks).extracting(PolicyChunk::content).containsExactly(p1, p2, p3, p4);
    }

    @Test
    void paragraphsAreNeverCutInTheMiddle() {
        String p1 = text(90, 'a');
        String p2 = text(90, 'b');
        String p3 = text(90, 'c');

        List<PolicyChunk> chunks = chunker.chunk(TITLE, policy(section(null, List.of("S"), p1, p2, p3)));

        // 90 + 2 + 90 = 182 fits in 200; adding the third would not
        assertThat(chunks).extracting(PolicyChunk::content).containsExactly(p1 + "\n\n" + p2, p3);
    }

    @Test
    void aSingleParagraphLongerThanTheLimitStaysWhole() {
        String huge = text(500, 'x');

        List<PolicyChunk> chunks = chunker.chunk(TITLE, policy(section(null, List.of("S"), huge)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo(huge);
    }

    @Test
    void aTinySectionIsMergedIntoThePreviousSiblingWithItsHeading() {
        List<PolicyChunk> chunks = chunker.chunk(TITLE, policy(
                section("q1", List.of("1. Alcohol", "More Details:"), text(120, 'a')),
                section("q1", List.of("1. Alcohol", "Examples of What is Allowed:"), "Beer kits")));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("Doc > 1. Alcohol > More Details:");
        assertThat(chunks.get(0).content()).isEqualTo(text(120, 'a') + "\n\nExamples of What is Allowed:\nBeer kits");
        assertThat(chunks.get(0).metadata()).containsEntry("merged_headings", List.of("Examples of What is Allowed:"));
    }

    @Test
    void aTinySectionWithNoPreviousSiblingIsMergedIntoTheNextOne() {
        List<PolicyChunk> chunks = chunker.chunk(TITLE, policy(
                section("q1", List.of("1. Alcohol", "Note"), "Short note"),
                section("q1", List.of("1. Alcohol", "More Details:"), text(120, 'b'))));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("Doc > 1. Alcohol > More Details:");
        assertThat(chunks.get(0).content()).isEqualTo("Note\nShort note\n\n" + text(120, 'b'));
    }

    @Test
    void tinySectionsUnderDifferentParentsAreNotMerged() {
        List<PolicyChunk> chunks = chunker.chunk(TITLE, policy(
                section(null, List.of("P1", "X"), "tiny one"),
                section(null, List.of("P2", "Y"), "tiny two")));

        assertThat(chunks).hasSize(2);
    }

    @Test
    void tinyTopLevelSectionsStayAlone() {
        List<PolicyChunk> chunks = chunker.chunk(TITLE, policy(
                section(null, List.of("A"), "tiny"),
                section(null, List.of("B"), "also tiny")));

        assertThat(chunks).extracting(PolicyChunk::sectionPath).containsExactly("Doc > A", "Doc > B");
    }

    @Test
    void aMergeThatWouldExceedTheLimitIsNotDone() {
        List<PolicyChunk> chunks = chunker.chunk(TITLE, policy(
                section(null, List.of("P", "Big"), text(190, 'a')),
                section(null, List.of("P", "Tiny"), "tiny text")));

        assertThat(chunks).hasSize(2);
    }

    @Test
    void emptyPolicyAndBlankUnitsGiveNoChunks() {
        assertThat(chunker.chunk(TITLE, policy())).isEmpty();
        assertThat(chunker.chunk(TITLE, policy(section(null, List.of("A"), "  ", "")))).isEmpty();
    }

    @Test
    void contentHashIsStableAndDependsOnContent() {
        PolicyChunk first = chunker.chunk(TITLE, policy(section(null, List.of("A"), text(60, 'a')))).get(0);
        PolicyChunk again = chunker.chunk(TITLE, policy(section(null, List.of("A"), text(60, 'a')))).get(0);
        PolicyChunk other = chunker.chunk(TITLE, policy(section(null, List.of("A"), text(60, 'b')))).get(0);

        assertThat(first.contentHash()).matches("[0-9a-f]{64}").isEqualTo(again.contentHash()).isNotEqualTo(other.contentHash());
    }

    @Test
    void embeddingTextStartsWithTheSectionPath() {
        PolicyChunk chunk = chunker.chunk(TITLE, policy(section(null, List.of("A", "B"), text(60, 'a')))).get(0);

        assertThat(chunk.embeddingText()).isEqualTo("Doc > A > B\n" + text(60, 'a'));
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new PolicyChunker.Config(50, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyChunker.Config(50, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
