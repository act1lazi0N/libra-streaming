package com.libra.streaming.recommendation.freshness.application;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.util.Map;
import java.util.Set;

/** The broker's view of an input topic and of the Analytics consumer group that applies it. */
public interface ConsumerProgress {
    /**
     * Partition number to the offset after the last record a read-committed consumer can see now.
     *
     * An empty map when the topic does not exist: no record was ever produced to it.
     *
     * @throws RuntimeException when the broker cannot answer
     */
    Map<Integer, Long> endOffsets(InputTopic topic);

    /**
     * Partition number to the group's committed next offset, or the partition's log start offset when the group has
     * committed none. Committed offsets move only after the database commit of the record before them.
     *
     * @throws RuntimeException when the broker cannot answer
     */
    Map<Integer, Long> positions(InputTopic topic, Set<Integer> partitions);
}
