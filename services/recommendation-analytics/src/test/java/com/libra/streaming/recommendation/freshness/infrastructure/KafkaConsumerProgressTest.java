package com.libra.streaming.recommendation.freshness.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.freshness.domain.Watermark;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.BrokerNotAvailableException;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The admin answers that are not plain offsets; the offsets themselves run against a real broker in the IT. */
class KafkaConsumerProgressTest {
    private final Admin admin = mock(Admin.class);
    private final KafkaConsumerProgress progress = new KafkaConsumerProgress(admin, Duration.ofSeconds(1));

    @Test
    void aTopicThatDoesNotExistHasNothingToWaitFor() {
        describe(failed(new UnknownTopicOrPartitionException("canary-broker-text")));

        Map<Integer, Long> ends = progress.endOffsets(InputTopic.PLAYBACK);

        assertThat(ends).isEmpty();
        // A quiet topic is not a fault: the watermark is reached at once and the checkpoint can advance.
        assertThat(new Watermark(Instant.EPOCH, ends).reachedBy(Map.of())).isTrue();
    }

    @Test
    void anyOtherBrokerFailureFailsTheTickWithoutBrokerText() {
        describe(failed(new BrokerNotAvailableException("canary-broker-text")));

        assertThatThrownBy(() -> progress.endOffsets(InputTopic.PLAYBACK)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Kafka admin request failed");
    }

    @Test
    void aSlowBrokerFailsTheTickWithinTheBudget() {
        describe(new KafkaFutureImpl<>()); // never completes
        long started = System.nanoTime();

        assertThatThrownBy(() -> progress.endOffsets(InputTopic.PLAYBACK)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Kafka admin request timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aGroupThatNeverCommittedHasNoPositionsInsteadOfFailing() {
        var offsets = mock(ListConsumerGroupOffsetsResult.class);
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> missing = failed(new GroupIdNotFoundException("canary"));
        when(offsets.partitionsToOffsetAndMetadata()).thenReturn(missing);
        when(admin.listConsumerGroupOffsets(anyString())).thenReturn(offsets);

        // No partition asked for: the group lookup alone must not fail the tick.
        assertThat(progress.positions(InputTopic.PLAYBACK, Set.of())).isEmpty();
    }

    private void describe(KafkaFuture<Map<String, TopicDescription>> answer) {
        var result = mock(DescribeTopicsResult.class);
        when(result.allTopicNames()).thenReturn(answer);
        when(admin.describeTopics(anyCollection())).thenReturn(result);
    }

    private static <T> KafkaFuture<T> failed(Throwable cause) {
        var future = new KafkaFutureImpl<T>();
        future.completeExceptionally(cause);
        return future;
    }
}
