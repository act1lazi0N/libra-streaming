package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One synchronous handler slot, one heartbeat slot, and no in-memory job backlog. */
public final class BoundedMediaWorker implements AutoCloseable {
    private final JobLeases leases;
    private final MediaJobHandler handler;
    private final Settings settings;
    private final Consumer<String> report;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService jobs = Executors.newSingleThreadScheduledExecutor(
            task -> Thread.ofPlatform().daemon().name("media-job").unstarted(task));
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            task -> Thread.ofPlatform().daemon().name("media-lease").unstarted(task));

    public BoundedMediaWorker(JobLeases leases, MediaJobHandler handler, Settings settings, Consumer<String> report) {
        this.leases = leases;
        this.handler = handler;
        this.settings = settings;
        this.report = report;
    }

    public synchronized void start() {
        if (closed.get()) { throw new IllegalStateException("Worker is closed"); }
        if (running.compareAndSet(false, true)) {
            jobs.scheduleWithFixedDelay(this::poll, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void poll() {
        if (!running.get()) { return; }
        try {
            leases.claim(settings.leaseDuration()).ifPresent(this::process);
        } catch (RuntimeException exception) {
            report.accept("WORKER_DATABASE_UNAVAILABLE");
        }
    }

    private void process(JobLease lease) {
        if (!running.get()) {
            leases.release(lease);
            return;
        }
        var lost = new AtomicBoolean();
        var active = new AtomicBoolean(true);
        Object renewalGate = new Object();
        Thread owner = Thread.currentThread();
        var renewal = heartbeat.scheduleWithFixedDelay(() -> {
            synchronized (renewalGate) {
                if (!active.get() || lost.get() || !running.get()) { return; }
                try {
                    if (leases.renew(lease, settings.leaseDuration())) { return; }
                } catch (RuntimeException exception) {
                    report.accept("WORKER_LEASE_UNAVAILABLE");
                }
                lost.set(true);
                owner.interrupt();
            }
        }, settings.renewInterval().toMillis(), settings.renewInterval().toMillis(), TimeUnit.MILLISECONDS);
        boolean finalized = false;
        try {
            MediaJobHandler.Outcome outcome;
            try {
                outcome = handler.process(lease, () -> !running.get() || lost.get());
                if (outcome == null) { outcome = MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED); }
            } catch (InterruptedException exception) {
                if (!running.get() || lost.get()) { return; }
                outcome = MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED);
            } catch (RuntimeException exception) {
                outcome = MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED);
            }
            synchronized (renewalGate) {
                if (!running.get() || lost.get()) { return; }
                // Finish any in-flight renewal before finalization. A late heartbeat cannot interrupt the next job.
                active.set(false);
                finalized = true;
                if (outcome.permanent()) { leases.fail(lease, outcome.failure()); }
                else { leases.retry(lease, outcome.failure(), settings.retryDelay()); }
            }
        } finally {
            synchronized (renewalGate) {
                active.set(false);
                renewal.cancel(false);
                Thread.interrupted();
                // Graceful cancellation relinquishes only a still-current lease. It is not a processing failure.
                if (!finalized && !running.get() && !lost.get()) { leases.release(lease); }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        running.set(false);
        jobs.shutdownNow();
        try {
            if (!jobs.awaitTermination(settings.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                report.accept("WORKER_SHUTDOWN_TIMEOUT");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            heartbeat.shutdownNow();
        }
        // An uncooperative handler retains no renewable authority; expiry permits another process to recover.
    }

    public record Settings(Duration pollInterval, Duration leaseDuration, Duration renewInterval,
            Duration retryDelay, Duration shutdownTimeout) {
        public Settings {
            bounded(pollInterval, Duration.ofMillis(10), Duration.ofMinutes(1));
            bounded(leaseDuration, Duration.ofMillis(100), Duration.ofMinutes(5));
            bounded(renewInterval, Duration.ofMillis(10), leaseDuration.dividedBy(3));
            bounded(retryDelay, Duration.ofMillis(10), Duration.ofHours(1));
            bounded(shutdownTimeout, Duration.ofMillis(10), Duration.ofSeconds(30));
        }
        private static void bounded(Duration value, Duration min, Duration max) {
            if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new IllegalArgumentException("Invalid worker timing");
            }
        }
    }
}
