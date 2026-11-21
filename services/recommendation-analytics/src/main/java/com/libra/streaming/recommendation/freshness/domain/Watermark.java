package com.libra.streaming.recommendation.freshness.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A candidate checkpoint: the instant {@code at}, taken first, and the end offset of every partition of the topic,
 * read after it. A record that was on the topic at {@code at} has an offset below its partition's end offset. Once the
 * consumer group's committed position reaches every end offset, each such record was applied and its database
 * transaction committed (the offset is committed only after that), so {@code at} can be confirmed.
 *
 * <p>The watermark waits for exactly those records, not for a lag of zero: under steady traffic a fresh record is
 * almost always in flight, and a "lag is zero" rule would never confirm anything while the consumer keeps up.
 *
 * @param endOffsets partition number to the offset after the last record a read-committed consumer could see
 */
public record Watermark(Instant at, Map<Integer, Long> endOffsets) {
    public Watermark {
        Objects.requireNonNull(at, "at");
        endOffsets = Map.copyOf(endOffsets);
        endOffsets.values().forEach(offset -> {
            if (offset < 0) { throw new IllegalArgumentException("An end offset is never negative"); }
        });
    }

    /**
     * @param positions partition number to the group's next offset to read; for a partition without a committed
     *        offset, its log start offset (nothing before it can still be read). A missing partition has not been
     *        consumed at all.
     */
    public boolean reachedBy(Map<Integer, Long> positions) {
        for (Map.Entry<Integer, Long> end : endOffsets.entrySet()) {
            if (end.getValue() == 0) { continue; }
            Long position = positions.get(end.getKey());
            if (position == null || position < end.getValue()) { return false; }
        }
        return true;
    }
}
