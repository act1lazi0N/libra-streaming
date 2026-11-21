package com.libra.streaming.recommendation.statistics.domain;

import com.libra.streaming.recommendation.freshness.domain.Freshness;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The inclusive UTC date range of a statistics query (RA01.1). Analytics checks it itself instead of trusting Core.
 */
public record StatisticsWindow(LocalDate from, LocalDate to) {
    public static final int MAX_DAYS = 31;

    public StatisticsWindow {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) { throw new InvalidWindowException("from is after to"); }
        if (days(from, to) > MAX_DAYS) { throw new InvalidWindowException("more than 31 days"); }
    }

    /**
     * A window Core may ask for at {@code now}. "Today" is the UTC date of {@code now} plus the skew margin, so a
     * request Core accepted just after UTC midnight is not refused by a clock still on the previous day.
     */
    public static StatisticsWindow requested(LocalDate from, LocalDate to, Instant now) {
        var window = new StatisticsWindow(from, to);
        if (to.isAfter(DayContribution.dayOf(now.plus(Freshness.SKEW_MARGIN)))) {
            throw new InvalidWindowException("to is after today");
        }
        return window;
    }

    public long days() { return days(from, to); }

    private static long days(LocalDate from, LocalDate to) { return ChronoUnit.DAYS.between(from, to) + 1; }

    /** The message is fixed text naming the broken rule; it never quotes a request value. */
    public static final class InvalidWindowException extends RuntimeException {
        InvalidWindowException(String rule) { super(rule, null, false, false); }
    }
}
