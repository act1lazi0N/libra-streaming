package com.libra.streaming.recommendation.preferences.domain;

/**
 * Bounds on how much watched time can count towards genre interests (RA05.1).
 *
 * @param windowDays the interest window in UTC days, today included
 * @param unknownDurationCapMs the most one item counts when the catalog gives no duration for it
 * @param itemCapMs the most one item counts in any case, even when its duration is longer
 * @param titleCapMs the most one movie or series counts, whatever its items add up to
 */
public record InterestRules(int windowDays, long unknownDurationCapMs, long itemCapMs, long titleCapMs) {
    /** 30 days; one item counts at most one full watch (2 h when unknown, 4 h at most); one title at most 6 h. */
    public static final InterestRules DEFAULT = new InterestRules(30, 7_200_000L, 14_400_000L, 21_600_000L);

    public InterestRules {
        if (windowDays < 1 || windowDays > 366) { throw new IllegalArgumentException("windowDays"); }
        if (unknownDurationCapMs < 1 || itemCapMs < unknownDurationCapMs || titleCapMs < itemCapMs) {
            throw new IllegalArgumentException("caps must satisfy 0 < unknownDurationCap <= itemCap <= titleCap");
        }
    }

    /** Watched time beyond one full watch of the item is a repeat and adds nothing. */
    public long itemCap(Long durationMs) {
        return durationMs == null ? unknownDurationCapMs : Math.min(durationMs, itemCapMs);
    }
}
