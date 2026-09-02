package com.tessera.fleet.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextSimilarityTest {

    @Test
    void exactMatchScoresOne() {
        assertThat(TextSimilarity.score("North Station Yard", "North Station Yard")).isEqualTo(1.0);
    }

    @Test
    void prefixBeatsSubstringBeatsTokenBeatsUnrelated() {
        double prefix = TextSimilarity.score("north", "North Station Yard");
        double substring = TextSimilarity.score("station", "North Station Yard");
        double token = TextSimilarity.score("yard north", "North Station Yard");
        double unrelated = TextSimilarity.score("harbor terminal", "North Station Yard");

        assertThat(prefix).isGreaterThan(substring);
        assertThat(substring).isGreaterThan(unrelated);
        assertThat(token).isGreaterThan(unrelated);
        assertThat(unrelated).isLessThan(0.3);
    }

    @Test
    void toleratesTypos() {
        double typo = TextSimilarity.score("comon depot", "Common Depot");
        double wrong = TextSimilarity.score("common depot", "Downtown Crossing Depot");
        assertThat(typo).isGreaterThan(0.35);
        assertThat(typo).isGreaterThan(wrong);
    }

    @Test
    void isCaseAndPunctuationInsensitive() {
        assertThat(TextSimilarity.score("st. mary's", "St Marys"))
                .isGreaterThan(0.6);
    }

    @Test
    void emptyInputsScoreZero() {
        assertThat(TextSimilarity.score("", "anything")).isZero();
        assertThat(TextSimilarity.score("anything", "")).isZero();
        assertThat(TextSimilarity.score(null, "x")).isZero();
    }
}
