package com.libra.streaming.recommendation.playback.domain;

import java.time.Instant;

/**
 * A session's qualifying instant (the {@code occurredAt} of its qualifying event with the lowest sequence) before and
 * after one fact was stored. Equal when the fact did not change it; {@code before} is null for a session that had not
 * qualified, {@code after} is null for one that still has not.
 */
public record Qualification(Instant before, Instant after) {
    public Qualification {
        if (before != null && after == null) { throw new IllegalStateException("A qualified session never loses its view"); }
    }
}
