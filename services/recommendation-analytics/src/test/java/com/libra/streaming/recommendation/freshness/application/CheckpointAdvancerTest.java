package com.libra.streaming.recommendation.freshness.application;

import com.libra.streaming.recommendation.freshness.domain.Freshness;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static com.libra.streaming.recommendation.freshness.application.CheckpointAdvancer.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;

class CheckpointAdvancerTest {
    private static final Instant START = Instant.parse("2026-09-07T12:00:00Z");
    private final MutableClock clock = new MutableClock();
    private final MemoryCheckpoints store = new MemoryCheckpoints();
    private final FakeConsumers consumers = new FakeConsumers(clock);
    private final FakeBackfill backfill = new FakeBackfill();
    private final CheckpointAdvancer advancer = new CheckpointAdvancer(consumers, backfill,
            new ProjectionFreshness(store, clock), clock);

    @Test
    void quietTopicsConfirmTheInstantTakenBeforeReadingEndOffsets() {
        consumers.shiftDuringEndRead = Duration.ofSeconds(1);

        assertThat(advancer.tick().values()).containsOnly(CONFIRMED);

        assertThat(store.confirmed()).hasSize(3).allSatisfy((topic, at) ->
                assertThat(at).isEqualTo(stored(consumers.clockAtEndRead.get(topic))));
        assertThat(store.confirmed().get(InputTopic.CATALOG)).isEqualTo(stored(START));
        assertThat(clock.instant()).isEqualTo(START.plusSeconds(3));
        assertThat(consumers.endReads).containsExactly(InputTopic.CATALOG, InputTopic.PROFILES, InputTopic.PLAYBACK);
    }

    @Test
    void busyTopicsKeepOldWatermarksThenTakeNewOnesOnFollowingTick() {
        consumers.position = 4;
        assertThat(advancer.tick().values()).containsOnly(WAITING);
        assertThat(store.confirmed()).isEmpty();

        clock.now = START.plusSeconds(60);
        consumers.position = 5;
        assertThat(advancer.tick().values()).containsOnly(CONFIRMED);
        assertThat(store.confirmed().values()).containsOnly(stored(START));
        assertThat(consumers.endReads).hasSize(3);

        clock.now = START.plusSeconds(120);
        assertThat(advancer.tick().values()).containsOnly(CONFIRMED);
        assertThat(store.confirmed().values()).containsOnly(stored(START.plusSeconds(120)));
        assertThat(consumers.endReads).hasSize(6);
    }

    @Test
    void recordsArrivingAfterWatermarkDoNotBlockItsConfirmation() {
        consumers.position = 4;
        assertThat(advancer.tick().values()).containsOnly(WAITING);
        consumers.end = 100;
        consumers.position = 5;
        clock.now = START.plusSeconds(60);

        assertThat(advancer.tick().values()).containsOnly(CONFIRMED);
        assertThat(store.confirmed().values()).containsOnly(stored(START));
        assertThat(consumers.endReads).hasSize(3);
        assertThat(advancer.tick().values()).containsOnly(WAITING);
        assertThat(consumers.endReads).hasSize(6);
    }

    @Test
    void incompleteBackfillWaitsWithoutConfirmingAndRetainsEarlierWatermark() {
        backfill.complete = false;
        assertThat(advancer.tick().values()).containsOnly(BACKFILLING);
        assertThat(store.confirmed()).isEmpty();
        assertThat(consumers.positionReads).isEmpty();
        backfill.complete = true;
        clock.now = START.plusSeconds(60);

        assertThat(advancer.tick().values()).containsOnly(CONFIRMED);
        assertThat(store.confirmed().values()).containsOnly(stored(START));
        assertThat(consumers.endReads).hasSize(3);
    }

    @Test
    void endOffsetFailureIsIsolatedToOneTopic() {
        consumers.failedEnds.add(InputTopic.CATALOG);

        assertThat(advancer.tick()).isEqualTo(Map.of(
                InputTopic.CATALOG, FAILED, InputTopic.PROFILES, CONFIRMED, InputTopic.PLAYBACK, CONFIRMED));
        assertThat(store.confirmed()).doesNotContainKey(InputTopic.CATALOG)
                .containsEntry(InputTopic.PROFILES, stored(START)).containsEntry(InputTopic.PLAYBACK, stored(START));
    }

    @Test
    void positionFailureKeepsWatermarkForNextSuccessfulTick() {
        consumers.failedPositions.add(InputTopic.PLAYBACK);
        assertThat(advancer.tick()).containsEntry(InputTopic.PLAYBACK, FAILED);
        assertThat(store.confirmed()).doesNotContainKey(InputTopic.PLAYBACK);
        consumers.failedPositions.clear();
        clock.now = START.plusSeconds(60);

        assertThat(advancer.tick()).containsEntry(InputTopic.PLAYBACK, CONFIRMED);
        assertThat(store.confirmed()).containsEntry(InputTopic.PLAYBACK, stored(START));
        assertThat(consumers.endReads.stream().filter(topic -> topic == InputTopic.PLAYBACK).count()).isEqualTo(1);
    }

    @Test
    void backwardsClockMakesFutureWatermarkFailWithoutStoringAnything() {
        consumers.position = 4;
        assertThat(advancer.tick().values()).containsOnly(WAITING);
        // Before the stored value (watermark less the margin): confirming it now would be a future checkpoint.
        clock.now = START.minusSeconds(6);
        consumers.position = 5;

        assertThat(advancer.tick().values()).containsOnly(FAILED);
        assertThat(store.confirmed()).isEmpty();
        assertThat(store.attempts).isEmpty();
    }

    @Test
    void olderWatermarkIsConfirmedAndClearedEvenWhenStoreDoesNotAdvance() {
        consumers.position = 4;
        assertThat(advancer.tick().values()).containsOnly(WAITING);
        Instant newer = START.plusSeconds(30);
        for (InputTopic topic : InputTopic.values()) { store.advance(topic, newer); }
        store.attempts.clear();
        clock.now = START.plusSeconds(60);
        consumers.position = 5;

        assertThat(advancer.tick().values()).containsOnly(CONFIRMED);
        assertThat(store.confirmed().values()).containsOnly(newer);
        assertThat(store.attempts).hasSize(3).allSatisfy(attempt -> {
            assertThat(attempt.at()).isEqualTo(stored(START));
            assertThat(attempt.advanced()).isFalse();
        });
        assertThat(advancer.tick().values()).containsOnly(CONFIRMED);
        assertThat(store.confirmed().values()).containsOnly(stored(clock.now));
        assertThat(consumers.endReads).hasSize(6);
    }

    /** The value a confirmed watermark is stored as: its instant less the skew margin Core needs. */
    private static Instant stored(Instant watermark) { return watermark.minus(Freshness.SKEW_MARGIN); }

    private static final class MutableClock extends Clock {
        private Instant now = START;

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) { throw new AssertionError("Only UTC is used by checkpoint tests"); }
            return this;
        }

        @Override
        public Instant instant() { return now; }
    }

    private record Attempt(InputTopic topic, Instant at, boolean advanced) {}

    private static final class MemoryCheckpoints implements CheckpointStore {
        private final Map<InputTopic, Instant> confirmed = new EnumMap<>(InputTopic.class);
        private final List<Attempt> attempts = new ArrayList<>();

        @Override
        public Map<InputTopic, Instant> confirmed() { return Map.copyOf(confirmed); }

        @Override
        public boolean advance(InputTopic topic, Instant at) {
            Instant previous = confirmed.get(topic);
            boolean advanced = previous == null || previous.isBefore(at);
            attempts.add(new Attempt(topic, at, advanced));
            if (advanced) { confirmed.put(topic, at); }
            return advanced;
        }
    }

    private static final class FakeBackfill implements BackfillProgress {
        private boolean complete = true;

        @Override
        public boolean complete(InputTopic topic) { return complete; }
    }

    private static final class FakeConsumers implements ConsumerProgress {
        private final MutableClock clock;
        private final Map<InputTopic, Instant> clockAtEndRead = new EnumMap<>(InputTopic.class);
        private final List<InputTopic> endReads = new ArrayList<>();
        private final List<InputTopic> positionReads = new ArrayList<>();
        private final Set<InputTopic> failedEnds = EnumSet.noneOf(InputTopic.class);
        private final Set<InputTopic> failedPositions = EnumSet.noneOf(InputTopic.class);
        private Duration shiftDuringEndRead = Duration.ZERO;
        private long end = 5;
        private long position = 5;

        private FakeConsumers(MutableClock clock) { this.clock = clock; }

        @Override
        public Map<Integer, Long> endOffsets(InputTopic topic) {
            endReads.add(topic);
            clockAtEndRead.put(topic, clock.instant());
            clock.now = clock.now.plus(shiftDuringEndRead);
            if (failedEnds.contains(topic)) { throw new IllegalStateException("synthetic end-offset failure"); }
            return Map.of(0, end);
        }

        @Override
        public Map<Integer, Long> positions(InputTopic topic, Set<Integer> partitions) {
            positionReads.add(topic);
            assertThat(partitions).containsExactly(0);
            if (failedPositions.contains(topic)) { throw new IllegalStateException("synthetic position failure"); }
            return Map.of(0, position);
        }
    }
}
