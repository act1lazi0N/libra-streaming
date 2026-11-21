package com.libra.streaming.recommendation.playback.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Running totals of one playback session, built only from the facts received so far.
 *
 * @param acceptedWatchedMs sum of the accepted increments received; the only watched-time figure (RA01.1)
 * @param maxTotalWatchedMs Core's cumulative total at {@code maxSequence}; never added, only compared
 * @param qualifiedSequence the lowest sequence received with {@code qualified = true}; null while none arrived
 * @param profileId null once the profile is deleted
 */
public record SessionTotals(UUID sessionId, UUID accountId, UUID profileId, UUID contentId, int factCount,
        long acceptedWatchedMs, long maxSequence, long maxTotalWatchedMs, Long qualifiedSequence, Instant qualifiedAt,
        Instant firstOccurredAt, Instant lastOccurredAt) {
    /** Every fact up to the highest sequence has arrived; sequences start at 1 and are unique per session. */
    public boolean complete() { return factCount == maxSequence; }

    /** Watched time Core accepted but whose increments have not arrived yet; zero once the session is complete. */
    public long missingWatchedMs() { return Math.max(0, maxTotalWatchedMs - acceptedWatchedMs); }
}
