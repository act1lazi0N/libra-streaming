package com.libra.streaming.recommendation.freshness.application;

import com.libra.streaming.recommendation.freshness.domain.Freshness;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.freshness.domain.Projection;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Answers how fresh a projection is and records confirmed progress. Request handling never advances it. */
public class ProjectionFreshness {
    private final CheckpointStore store;
    private final Clock clock;

    public ProjectionFreshness(CheckpointStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Empty until every input of the projection has a confirmed checkpoint. */
    public Optional<Instant> updatedAt(Projection projection) {
        Map<InputTopic, Instant> confirmed = store.confirmed();
        return Freshness.updatedAt(projection.inputs().stream()
                .map(topic -> Optional.ofNullable(confirmed.get(topic))).toList(), clock.instant());
    }

    /** A checkpoint is an instant already reached; a future one would let stale data look fresh. */
    public boolean confirm(InputTopic topic, Instant at) {
        if (at.isAfter(clock.instant())) { throw new IllegalArgumentException("A checkpoint cannot be in the future"); }
        return store.advance(topic, at);
    }
}
