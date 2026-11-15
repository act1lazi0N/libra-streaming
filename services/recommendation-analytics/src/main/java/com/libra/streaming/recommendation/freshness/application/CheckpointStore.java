package com.libra.streaming.recommendation.freshness.application;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.time.Instant;
import java.util.Map;

/** Durable confirmed checkpoints. Implementations join the transaction of the caller when one is active. */
public interface CheckpointStore {
    /** One consistent read of every confirmed checkpoint; topics without one are absent from the map. */
    Map<InputTopic, Instant> confirmed();

    /** Moves the checkpoint forward only. Returns false when the stored value is already at or after {@code at}. */
    boolean advance(InputTopic topic, Instant at);
}
