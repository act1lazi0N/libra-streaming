package com.libra.streaming.recommendation.statistics.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class StatisticsWindowTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    @Test
    void rejectsReversedDates() {
        assertThatThrownBy(() -> new StatisticsWindow(TODAY, TODAY.minusDays(1)))
                .isInstanceOf(StatisticsWindow.InvalidWindowException.class);
    }

    @Test
    void acceptsExactlyThirtyOneInclusiveDays() {
        var window = new StatisticsWindow(TODAY.minusDays(30), TODAY);
        assertThat(window.from()).isEqualTo(TODAY.minusDays(30));
        assertThat(window.to()).isEqualTo(TODAY);
        assertThat(window.days()).isEqualTo(31);
    }

    @Test
    void rejectsThirtyTwoInclusiveDays() {
        assertThatThrownBy(() -> new StatisticsWindow(TODAY.minusDays(31), TODAY))
                .isInstanceOf(StatisticsWindow.InvalidWindowException.class);
    }

    @Test
    void sameDayWindowHasOneDay() {
        assertThat(new StatisticsWindow(TODAY, TODAY).days()).isEqualTo(1);
    }

    @Test
    void countsBothEndsAcrossMonthBoundary() {
        assertThat(new StatisticsWindow(LocalDate.of(2026, 8, 31), TODAY).days()).isEqualTo(8);
    }

    @Test
    void requestedWindowAcceptsUtcToday() {
        assertThat(StatisticsWindow.requested(TODAY.minusDays(1), TODAY,
                Instant.parse("2026-09-07T00:00:00Z")))
                .isEqualTo(new StatisticsWindow(TODAY.minusDays(1), TODAY));
    }

    @Test
    void tomorrowIsRejectedBeforeSkewMarginReachesMidnight() {
        assertThatThrownBy(() -> StatisticsWindow.requested(TODAY, TODAY.plusDays(1),
                Instant.parse("2026-09-07T23:59:54Z")))
                .isInstanceOf(StatisticsWindow.InvalidWindowException.class);
    }

    @Test
    void tomorrowIsAcceptedAtAndWithinFiveSecondSkewMargin() {
        for (String now : List.of("2026-09-07T23:59:55Z", "2026-09-07T23:59:59Z")) {
            assertThat(StatisticsWindow.requested(TODAY, TODAY.plusDays(1), Instant.parse(now)))
                    .as("request at %s", now).isEqualTo(new StatisticsWindow(TODAY, TODAY.plusDays(1)));
        }
    }

    @Test
    void rejectionMessagesNeverContainDates() {
        List<Runnable> invalid = List.of(
                () -> new StatisticsWindow(TODAY, TODAY.minusDays(1)),
                () -> new StatisticsWindow(TODAY.minusDays(31), TODAY),
                () -> StatisticsWindow.requested(TODAY, TODAY.plusDays(1), Instant.parse("2026-09-07T12:00:00Z")));
        for (Runnable request : invalid) {
            Throwable failure = catchThrowable(request::run);
            assertThat(failure).isInstanceOf(StatisticsWindow.InvalidWindowException.class);
            assertThat(failure.getMessage()).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
        }
    }
}
