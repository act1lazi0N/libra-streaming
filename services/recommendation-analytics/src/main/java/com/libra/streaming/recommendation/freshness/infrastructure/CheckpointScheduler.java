package com.libra.streaming.recommendation.freshness.infrastructure;

import com.libra.streaming.recommendation.freshness.application.CheckpointAdvancer;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/**
 * Runs {@link CheckpointAdvancer#tick()} on one background thread once the application is ready. Each outcome is
 * counted; a change of outcome is logged once with the topic only, so a long broker outage does not flood the log.
 */
public final class CheckpointScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(CheckpointScheduler.class);

    private final CheckpointAdvancer advancer;
    private final Duration interval;
    private final MeterRegistry metrics;
    private final Map<InputTopic, CheckpointAdvancer.Outcome> last = new EnumMap<>(InputTopic.class);
    private ScheduledExecutorService executor;

    public CheckpointScheduler(CheckpointAdvancer advancer, Duration interval, MeterRegistry metrics) {
        this.advancer = advancer;
        this.interval = interval;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (executor != null) { return; }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "analytics-checkpoints");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void tick() {
        try {
            advancer.tick().forEach((topic, outcome) -> {
                metrics.counter("libra.analytics.freshness.ticks", "topic", topic.wireName(),
                        "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)).increment();
                CheckpointAdvancer.Outcome previous = last.put(topic, outcome);
                if (outcome == CheckpointAdvancer.Outcome.FAILED && previous != outcome) {
                    LOG.warn("Checkpoint of {} could not advance: broker, database or clock unavailable", topic.wireName());
                } else if (previous == CheckpointAdvancer.Outcome.FAILED && outcome != previous) {
                    LOG.info("Checkpoint of {} recovered ({})", topic.wireName(), outcome);
                }
            });
        } catch (Throwable unexpected) {
            // Anything thrown, an Error included, would cancel the periodic task for good and silently.
            LOG.error("Checkpoint tick failed unexpectedly ({})", unexpected.getClass().getName());
        }
    }

    @EventListener(ContextClosedEvent.class)
    public synchronized void stop() {
        if (executor != null) { executor.shutdownNow(); }
    }
}
