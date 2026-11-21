package com.libra.streaming.recommendation.statistics.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DayContributionTest {
    private static final LocalDate EARLY_DAY = LocalDate.of(2026, 9, 7);
    private static final LocalDate LATE_DAY = LocalDate.of(2026, 9, 8);
    private static final Instant EARLY = Instant.parse("2026-09-07T23:59:59.999Z");
    private static final Instant LATE = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void bucketsInstantsByUtcCalendarDay() {
        assertThat(DayContribution.dayOf(EARLY)).isEqualTo(EARLY_DAY);
        assertThat(DayContribution.dayOf(LATE)).isEqualTo(LATE_DAY);
        Instant localTomorrow = OffsetDateTime.parse("2026-09-08T01:00:00+07:00").toInstant();
        assertThat(DayContribution.dayOf(localTomorrow)).isEqualTo(EARLY_DAY);
    }

    @Test
    void watchedTimeBelongsToOccurredAtDay() {
        assertThat(DayContribution.of(EARLY, 1200, null, LATE)).containsExactly(
                new DayContribution(EARLY_DAY, 0, 1200), new DayContribution(LATE_DAY, 1, 0));
    }

    @Test
    void zeroWatchedTimeWithoutQualificationAddsNoEntry() {
        assertThat(DayContribution.of(EARLY, 0, null, null)).isEmpty();
    }

    @Test
    void firstQualificationAddsOneViewOnItsDay() {
        assertThat(DayContribution.of(LATE, 0, null, EARLY))
                .containsExactly(new DayContribution(EARLY_DAY, 1, 0));
    }

    @Test
    void movingQualificationEarlierRemovesOldViewAndAddsNewViewInDayOrder() {
        assertThat(DayContribution.of(LATE, 0, LATE, EARLY)).containsExactly(
                new DayContribution(EARLY_DAY, 1, 0), new DayContribution(LATE_DAY, -1, 0));
    }

    @Test
    void movingQualificationWithinOneDayAddsNoEntry() {
        assertThat(DayContribution.of(LATE, 0, LATE.plusSeconds(60), LATE)).isEmpty();
    }

    @Test
    void watchedTimeAndViewOnSameDayMergeIntoOneEntry() {
        assertThat(DayContribution.of(EARLY, 1500, null, EARLY))
                .containsExactly(new DayContribution(EARLY_DAY, 1, 1500));
    }

    @Test
    void qualificationCannotDisappear() {
        assertThatThrownBy(() -> DayContribution.of(LATE, 0, EARLY, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unchangedQualificationAddsOnlyWatchedTime() {
        assertThat(DayContribution.of(LATE, 0, EARLY, EARLY)).isEmpty();
        assertThat(DayContribution.of(LATE, 2000, EARLY, EARLY))
                .containsExactly(new DayContribution(LATE_DAY, 0, 2000));
    }

    @Test
    void recordRejectsNegativeWatchedTime() {
        assertThatThrownBy(() -> new DayContribution(EARLY_DAY, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
