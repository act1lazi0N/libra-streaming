package com.libra.streaming.recommendation.freshness.application;

import com.libra.streaming.recommendation.freshness.domain.Freshness;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.freshness.domain.Watermark;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Moves the confirmed checkpoints forward from consumer progress, never from request time or event time (RA01.1,
 * "Freshness"). Each topic holds at most one pending {@link Watermark}: it is confirmed, less the skew margin, once
 * the consumer group has committed past it, and the next watermark is taken on the following tick. A quiet topic is confirmed on the tick
 * that takes its watermark; a busy one about one tick later. A stalled consumer or an unreachable broker confirms
 * nothing, so {@code updatedAt} ages and Core reports the answer as stale; it never turns the answer into zeros.
 *
 * <p>Only the background scheduler calls {@link #tick()}; requests read checkpoints and never move them.
 */
public class CheckpointAdvancer {
    public enum Outcome {
        /** The pending watermark was reached and its instant confirmed. */
        CONFIRMED,
        /** The consumer group has not yet committed past the pending watermark. */
        WAITING,
        /** The topic's history is still being loaded; nothing is confirmed. */
        BACKFILLING,
        /** The broker, the database or the clock failed this tick; the pending watermark is kept. */
        FAILED
    }

    private final ConsumerProgress consumers;
    private final BackfillProgress backfill;
    private final ProjectionFreshness freshness;
    private final Clock clock;
    private final Map<InputTopic, Watermark> pending = new EnumMap<>(InputTopic.class);

    public CheckpointAdvancer(ConsumerProgress consumers, BackfillProgress backfill, ProjectionFreshness freshness,
            Clock clock) {
        this.consumers = consumers;
        this.backfill = backfill;
        this.freshness = freshness;
        this.clock = clock;
    }

    public synchronized Map<InputTopic, Outcome> tick() {
        Map<InputTopic, Outcome> outcomes = new EnumMap<>(InputTopic.class);
        for (InputTopic topic : InputTopic.values()) {
            Outcome outcome;
            try { outcome = advance(topic); }
            catch (RuntimeException failure) { outcome = Outcome.FAILED; }
            outcomes.put(topic, outcome);
        }
        return outcomes;
    }

    private Outcome advance(InputTopic topic) {
        Watermark watermark = pending.get(topic);
        if (watermark == null) {
            // The instant is taken before the end offsets are read: every record on the topic at that instant lies
            // below the offsets read afterwards. The other order could confirm an instant past an unread record.
            Instant at = clock.instant();
            watermark = new Watermark(at, consumers.endOffsets(topic));
            pending.put(topic, watermark);
        }
        if (!backfill.complete(topic)) { return Outcome.BACKFILLING; }
        if (!watermark.reachedBy(consumers.positions(topic, watermark.endOffsets().keySet()))) { return Outcome.WAITING; }
        // Everything available at `at` was applied, so this also holds for any earlier instant. The skew margin is
        // taken here, once: the stored value is then already what Core may see, and a request never changes it.
        freshness.confirm(topic, watermark.at().minus(Freshness.SKEW_MARGIN));
        pending.remove(topic);
        return Outcome.CONFIRMED;
    }
}
