package com.libra.streaming.recommendation.statistics.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What one accepted playback fact changes in one account's daily statistics (RA01.1, "Statistics semantics").
 *
 * @param qualifiedViews +1, 0 or -1: a session's view moves to an earlier day when a late qualifying event with a
 *        lower sequence arrives, so the day it held before loses it
 * @param acceptedWatchedMs never negative: watched time is only ever added
 */
public record DayContribution(LocalDate day, long qualifiedViews, long acceptedWatchedMs) {
    public DayContribution {
        Objects.requireNonNull(day, "day");
        if (acceptedWatchedMs < 0) { throw new IllegalArgumentException("Watched time is never removed"); }
    }

    /** The statistics day of an instant: its calendar date in UTC, never the server's zone. */
    public static LocalDate dayOf(Instant instant) { return LocalDate.ofInstant(instant, ZoneOffset.UTC); }

    /**
     * The contributions of one fact, merged per day, without empty entries, in ascending day order. Writers apply
     * them in that order, so two transactions touching the same rows always lock them in the same order.
     *
     * @param occurredAt the fact's {@code occurredAt}; buckets its watched time
     * @param qualifiedBefore the session's qualifying instant before this fact; null when it had none
     * @param qualifiedAfter the session's qualifying instant after this fact; null when it still has none
     */
    public static List<DayContribution> of(Instant occurredAt, long acceptedWatchedMs, Instant qualifiedBefore,
            Instant qualifiedAfter) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (qualifiedBefore != null && qualifiedAfter == null) {
            throw new IllegalArgumentException("A qualified session never loses its view");
        }
        TreeMap<LocalDate, long[]> days = new TreeMap<>();
        if (acceptedWatchedMs > 0) { days.computeIfAbsent(dayOf(occurredAt), day -> new long[2])[1] += acceptedWatchedMs; }
        if (qualifiedAfter != null && !qualifiedAfter.equals(qualifiedBefore)) {
            days.computeIfAbsent(dayOf(qualifiedAfter), day -> new long[2])[0] += 1;
            if (qualifiedBefore != null) { days.computeIfAbsent(dayOf(qualifiedBefore), day -> new long[2])[0] -= 1; }
        }
        List<DayContribution> contributions = new ArrayList<>(days.size());
        days.forEach((day, change) -> {
            if (change[0] != 0 || change[1] != 0) { contributions.add(new DayContribution(day, change[0], change[1])); }
        });
        return List.copyOf(contributions);
    }
}
