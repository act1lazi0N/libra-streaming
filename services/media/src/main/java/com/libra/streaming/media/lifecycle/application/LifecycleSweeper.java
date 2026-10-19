package com.libra.streaming.media.lifecycle.application;

import com.libra.streaming.media.lifecycle.domain.CleanupPlan;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One background slot that, every poll, expires a bounded batch of abandoned uploads and then cleans a bounded batch
 * of settled ones. Every decision comes from durable state, so any number of processes may run it and a restart
 * resumes where the database says the work stands. Nothing is kept in memory between passes.
 */
public final class LifecycleSweeper implements AutoCloseable {
    private final UploadExpiry expiry;
    private final CleanupPlans plans;
    private final ObjectCleanup cleanup;
    private final LifecycleEvents events;
    private final Settings settings;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            task -> Thread.ofPlatform().daemon().name("media-lifecycle").unstarted(task));

    public LifecycleSweeper(UploadExpiry expiry, CleanupPlans plans, ObjectCleanup cleanup, LifecycleEvents events,
            Settings settings) {
        this.expiry = Objects.requireNonNull(expiry);
        this.plans = Objects.requireNonNull(plans);
        this.cleanup = Objects.requireNonNull(cleanup);
        this.events = Objects.requireNonNull(events);
        this.settings = Objects.requireNonNull(settings);
    }

    public synchronized void start() {
        if (closed.get()) { throw new IllegalStateException("Sweeper is closed"); }
        if (running.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::sweep, settings.pollInterval().toMillis(),
                    settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void sweep() {
        if (running.get()) { pass(); }
    }

    /** One pass; public so tests and an operator path can drive it without the schedule. */
    public void pass() {
        try {
            int expired = expiry.expire(settings.batchSize());
            if (expired > 0) { events.expired(expired); }
        } catch (RuntimeException exception) {
            events.failed("EXPIRY_UNAVAILABLE");
        }
        List<CleanupPlan> due;
        try {
            due = plans.due(settings.writeGrace(), settings.batchSize());
        } catch (RuntimeException exception) {
            events.failed("CLEANUP_DATABASE_UNAVAILABLE");
            return;
        }
        for (var plan : due) {
            if (closed.get()) { return; }
            try {
                cleanup.clean(plan);
            } catch (CleanupStorage.Unavailable unavailable) {
                // Storage is down for every upload alike; the next pass starts over.
                events.failed("CLEANUP_STORAGE_UNAVAILABLE");
                return;
            } catch (ObjectCleanup.LayoutChanged changed) {
                events.failed("CLEANUP_LAYOUT_CHANGED");
            } catch (RuntimeException exception) {
                events.failed("CLEANUP_FAILED");
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        running.set(false);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(settings.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                events.failed("SHUTDOWN_TIMEOUT");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@code writeGrace} is how long after an upload settles its objects are left alone. It must exceed the longest
     * write admitted before the settlement: a browser PUT admitted before the upload deadline, or a storage request of
     * a worker whose lease had already run out. It is never shorter than five minutes.
     */
    public record Settings(Duration pollInterval, int batchSize, Duration writeGrace, Duration shutdownTimeout) {
        public Settings {
            bounded(pollInterval, Duration.ofSeconds(1), Duration.ofHours(1));
            bounded(writeGrace, Duration.ofMinutes(5), Duration.ofDays(7));
            bounded(shutdownTimeout, Duration.ofMillis(10), Duration.ofSeconds(30));
            if (batchSize < 1 || batchSize > 500) { throw new IllegalArgumentException("Invalid lifecycle batch"); }
        }

        private static void bounded(Duration value, Duration min, Duration max) {
            if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new IllegalArgumentException("Invalid lifecycle timing");
            }
        }
    }
}
