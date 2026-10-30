package com.thanhdatpb.listingguard.trademark.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.AlgorithmVersions;
import org.junit.jupiter.api.Test;

class SimilarityTest {

    @Test
    void equalTextsScoreOne() {
        assertThat(Similarity.score("zorvex", "zorvex")).isEqualTo(1.0);
        assertThat(Similarity.score("", "")).isEqualTo(1.0);
    }

    @Test
    void oneEditCostsOneOverTheLongerLength() {
        assertThat(Similarity.osaDistance("zorvex", "zorvx")).isEqualTo(1);
        assertThat(Similarity.score("zorvex", "zorvx")).isEqualTo(5.0 / 6.0);
        assertThat(Similarity.score("brask", "brisk")).isEqualTo(0.8);
        assertThat(Similarity.score("zorvex", "zorvexa")).isEqualTo(6.0 / 7.0);
    }

    @Test
    void aSwapOfNeighbouringLettersIsOneEdit() {
        assertThat(Similarity.osaDistance("zorvex", "zorvxe")).isEqualTo(1);
        assertThat(Similarity.osaDistance("ab", "ba")).isEqualTo(1);
    }

    @Test
    void isSymmetric() {
        assertThat(Similarity.score("quillmark", "quilmarc")).isEqualTo(Similarity.score("quilmarc", "quillmark"));
    }

    @Test
    void texts_with_nothing_in_common_score_zero() {
        assertThat(Similarity.score("abc", "xyz")).isEqualTo(0.0);
        assertThat(Similarity.score("abc", "")).isEqualTo(0.0);
    }

    @Test
    void countsCodePointsNotUtf16Units() {
        assertThat(Similarity.osaDistance("a😀b", "ab")).isEqualTo(1);
        assertThat(Similarity.score("a😀b", "a😀c")).isEqualTo(2.0 / 3.0);
    }

    @Test
    void theScoringVersionIsPartOfTheAlgorithmVersions() {
        assertThat(AlgorithmVersions.CURRENT.scoring()).isEqualTo(AlgorithmVersions.SCORING).isEqualTo("osa-similarity-1");
        assertThat(AlgorithmVersions.CURRENT.normalization()).isEqualTo("normalization-1");
        assertThat(AlgorithmVersions.CURRENT.matcher()).isEqualTo("matcher-1");
    }
}
