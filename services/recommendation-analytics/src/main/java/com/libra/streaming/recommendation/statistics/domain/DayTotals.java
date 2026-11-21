package com.libra.streaming.recommendation.statistics.domain;

import java.time.LocalDate;
import java.util.Objects;

/** The totals of one UTC day; {@code uniqueViewers} counts that day alone. */
public record DayTotals(LocalDate day, StatisticsTotals totals) {
    public DayTotals {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(totals, "totals");
    }
}
