package com.libra.streaming.media.events.application;

import com.libra.streaming.media.events.domain.OutboxDelivery;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Moves committed outbox events to the broker: lease one, publish it, record the acknowledgement. Delivery is
 * at least once. A crash between the broker's acknowledgement and {@code sent}, an acknowledgement slower than the
 * send timeout, or a lease that runs out mid-send can publish the same event again under the same event ID; Core
 * deduplicates by event ID and ignores versions it has already projected.
 */
public final class OutboxRelay implements AutoCloseable {
    /** Bounds one drain so a large backlog cannot starve the scheduler's shutdown check. */
    static final int MAX_PER_DRAIN = 100;

    private final OutboxEvents events;
    private final EventPublisher publisher;
    private final Settings settings;
    private final Consumer<String> report;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService relay = Executors.newSingleThreadScheduledExecutor(
            task -> Thread.ofPlatform().daemon().name("media-outbox").unstarted(task));

    public OutboxRelay(OutboxEvents events, EventPublisher publisher, Settings settings, Consumer<String> report) {
        this.events = events;
        this.publisher = publisher;
        this.settings = settings;
        this.report = report;
    }

    public synchronized void start() {
        if (closed.get()) { throw new IllegalStateException("Relay is closed"); }
        if (running.compareAndSet(false, true)) {
            relay.scheduleWithFixedDelay(this::drain, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Publishes due events until none is left, the broker fails, or the bound is reached. */
    void drain() {
        for (int i = 0; i < MAX_PER_DRAIN && !closed.get(); i++) {
            OutboxDelivery delivery;
            try {
                var claimed = events.claim(settings.leaseDuration());
                if (claimed.isEmpty()) { return; }
                delivery = claimed.get();
            } catch (RuntimeException exception) {
                report.accept("OUTBOX_DATABASE_UNAVAILABLE");
                return;
            }
            if (!deliver(delivery)) { return; }
        }
    }

    private boolean deliver(OutboxDelivery delivery) {
        try {
            publisher.publish(delivery, settings.sendTimeout());
        } catch (InterruptedException exception) {
            // Shutdown: the broker may or may not have the record. Returning it now only risks a duplicate.
            settle(() -> events.release(delivery));
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException exception) {
            report.accept("OUTBOX_BROKER_UNAVAILABLE");
            settle(() -> events.retry(delivery, settings.maxRetryDelay()));
            // A failing broker fails the next event too; wait for the next poll.
            return false;
        }
        try {
            if (!events.sent(delivery)) { report.accept("OUTBOX_DELIVERY_FENCED"); }
            return true;
        } catch (RuntimeException exception) {
            // The record is on the broker; the expired lease makes the event deliverable (and duplicated) again.
            report.accept("OUTBOX_DATABASE_UNAVAILABLE");
            return false;
        }
    }

    private void settle(BooleanSupplier write) {
        try {
            write.getAsBoolean();
        } catch (RuntimeException exception) {
            // The lease runs out on its own; the event is then claimable again.
            report.accept("OUTBOX_DATABASE_UNAVAILABLE");
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        running.set(false);
        relay.shutdownNow();
        try {
            if (!relay.awaitTermination(settings.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                report.accept("OUTBOX_SHUTDOWN_TIMEOUT");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The lease must outlive a send by a clear margin, so an acknowledged record is normally marked sent before
     * another relay could lease it again.
     */
    public record Settings(Duration pollInterval, Duration leaseDuration, Duration sendTimeout, Duration maxRetryDelay,
            Duration shutdownTimeout) {
        public Settings {
            bounded(pollInterval, Duration.ofMillis(10), Duration.ofMinutes(1));
            bounded(sendTimeout, Duration.ofMillis(100), Duration.ofMinutes(1));
            bounded(leaseDuration, sendTimeout.multipliedBy(2), Duration.ofMinutes(5));
            bounded(maxRetryDelay, Duration.ofSeconds(1), Duration.ofHours(1));
            bounded(shutdownTimeout, Duration.ofMillis(10), Duration.ofSeconds(30));
        }

        private static void bounded(Duration value, Duration min, Duration max) {
            if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new IllegalArgumentException("Invalid outbox timing");
            }
        }
    }
}
