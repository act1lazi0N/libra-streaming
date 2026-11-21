package com.libra.streaming.recommendation.freshness.application;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;

/**
 * Whether the history of a topic that Kafka may no longer hold has been loaded (the RA02.4 bootstrap). Until it has,
 * an empty or trimmed topic would let missing history look complete, so no checkpoint is confirmed.
 */
public interface BackfillProgress {
    /** @throws RuntimeException when the state cannot be read */
    boolean complete(InputTopic topic);
}
