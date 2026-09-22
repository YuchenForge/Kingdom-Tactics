package com.kingdom.worker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MatchEndDecisionTest {

    @Test
    void bothKeepZero_draw() {
        MatchEndDecision d = MatchEndDecision.decide(0, 0, 3, 10, 12);
        assertThat(d.isFinished()).isTrue();
        assertThat(d.isDraw()).isTrue();
        assertThat(d.winnerSeat()).isNull();
    }

    @Test
    void oneKeepZero_otherWins() {
        assertThat(MatchEndDecision.decide(0, 5, 2, 10, 10).winnerSeat()).isEqualTo(1);
        assertThat(MatchEndDecision.decide(8, 0, 2, 10, 10).winnerSeat()).isEqualTo(0);
    }

    @Test
    void round8_keep12vs10_higherKeepWins() {
        MatchEndDecision d = MatchEndDecision.decide(12, 10, 8, 1, 99);
        assertThat(d.isFinished()).isTrue();
        assertThat(d.winnerSeat()).isEqualTo(0);
    }

    @Test
    void round8_keepTied_gold8vs3_higherGoldWins() {
        MatchEndDecision d = MatchEndDecision.decide(10, 10, 8, 8, 3);
        assertThat(d.isFinished()).isTrue();
        assertThat(d.winnerSeat()).isEqualTo(0);
    }

    @Test
    void round8_keepAndGoldTied_draw() {
        MatchEndDecision d = MatchEndDecision.decide(10, 10, 8, 15, 15);
        assertThat(d.isFinished()).isTrue();
        assertThat(d.isDraw()).isTrue();
    }

    @Test
    void round7_bothKeepPositive_continues() {
        MatchEndDecision d = MatchEndDecision.decide(10, 10, 7, 1, 99);
        assertThat(d.isFinished()).isFalse();
        assertThat(d.winnerSeat()).isNull();
    }
}
