package com.libra.streaming.recommendation.ingestion.domain;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.time.Instant;
import java.util.UUID;

public final class DeadLetter {
    private DeadLetter() {}

    public record Captured(int dltPartition, long dltOffset, String sourceTopic, Integer sourcePartition,
            Long sourceOffset, String key, String body, String reason, String state) {}

    /** A parked record that can be redriven; {@code receivedAt} and {@code id} order the held rows. */
    public record Held(UUID id, Instant receivedAt, InputTopic topic, String key, String body) {}
}
