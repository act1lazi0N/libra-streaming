package com.libra.streaming.core.history.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResumeStateTest {
    @Test
    void completedHistoryRestartsAndUnfinishedHistoryClampsToDuration() {
        assertThat(new ResumeState(900, true).openingPosition(1000)).isZero();
        assertThat(new ResumeState(1200, false).openingPosition(1000)).isEqualTo(1000);
        assertThat(ResumeState.completes(949, 1000)).isFalse();
        assertThat(ResumeState.completes(950, 1000)).isTrue();
    }
}
