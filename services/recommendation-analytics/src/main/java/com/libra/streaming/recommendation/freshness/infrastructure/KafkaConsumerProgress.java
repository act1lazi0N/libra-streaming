package com.libra.streaming.recommendation.freshness.infrastructure;

import com.libra.streaming.recommendation.freshness.application.ConsumerProgress;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

/** Reads end offsets and committed group offsets through the Kafka admin API; never joins the consumer group. */
class KafkaConsumerProgress implements ConsumerProgress {
    private final Admin admin;
    private final Duration timeout;

    KafkaConsumerProgress(Admin admin, Duration timeout) {
        this.admin = admin;
        this.timeout = timeout;
    }

    @Override
    public Map<Integer, Long> endOffsets(InputTopic topic) {
        TopicDescription description;
        try { description = get(admin.describeTopics(List.of(topic.wireName())).allTopicNames()).get(topic.wireName()); }
        catch (TopicNotFound notFound) {
            // No record was ever produced to a topic that does not exist: nothing is waiting to be applied. A topic
            // created later only holds records produced after this watermark's instant.
            return Map.of();
        }
        if (description == null || description.partitions().isEmpty()) {
            throw new IllegalStateException("Input topic has no partitions");
        }
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        description.partitions().forEach(partition ->
                latest.put(new TopicPartition(topic.wireName(), partition.partition()), OffsetSpec.latest()));
        // Read committed, like the listeners: records of an open producer transaction are not available yet.
        return offsets(get(admin.listOffsets(latest, new ListOffsetsOptions(IsolationLevel.READ_COMMITTED)).all()));
    }

    @Override
    public Map<Integer, Long> positions(InputTopic topic, Set<Integer> partitions) {
        Map<TopicPartition, OffsetAndMetadata> committed;
        try { committed = get(admin.listConsumerGroupOffsets(topic.consumerGroup()).partitionsToOffsetAndMetadata()); }
        catch (GroupNotFound notFound) { committed = Map.of(); }
        Map<Integer, Long> positions = new HashMap<>();
        Map<TopicPartition, OffsetSpec> uncommitted = new HashMap<>();
        for (int partition : partitions) {
            var key = new TopicPartition(topic.wireName(), partition);
            OffsetAndMetadata offset = committed.get(key);
            if (offset != null) { positions.put(partition, offset.offset()); }
            else { uncommitted.put(key, OffsetSpec.earliest()); }
        }
        // A partition the group never committed has been read up to its log start: the records before it are gone,
        // and the ones after it are unread, so the watermark waits for them.
        if (!uncommitted.isEmpty()) { positions.putAll(offsets(get(admin.listOffsets(uncommitted).all()))); }
        return positions;
    }

    private static Map<Integer, Long> offsets(Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> results) {
        Map<Integer, Long> offsets = new HashMap<>();
        results.forEach((partition, info) -> offsets.put(partition.partition(), info.offset()));
        return offsets;
    }

    private <T> T get(KafkaFuture<T> future) {
        try { return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading consumer progress");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof GroupIdNotFoundException) { throw new GroupNotFound(); }
            if (failure.getCause() instanceof UnknownTopicOrPartitionException) { throw new TopicNotFound(); }
            // Broker messages are not propagated: the caller records the outcome only.
            throw new IllegalStateException("Kafka admin request failed");
        } catch (TimeoutException timedOut) {
            throw new IllegalStateException("Kafka admin request timed out");
        }
    }

    /** A group with no committed offset yet, which the broker may report as unknown. */
    private static final class GroupNotFound extends RuntimeException {
        GroupNotFound() { super(null, null, false, false); }
    }

    /** The input topic does not exist (yet). */
    private static final class TopicNotFound extends RuntimeException {
        TopicNotFound() { super(null, null, false, false); }
    }
}
