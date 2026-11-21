package com.libra.streaming.recommendation.bootstrap.infrastructure;

import com.libra.streaming.recommendation.bootstrap.application.Bootstrap;
import com.libra.streaming.recommendation.bootstrap.application.CoreSnapshots;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

public final class BootstrapRunner {
    private static final Logger LOG = LoggerFactory.getLogger(BootstrapRunner.class);
    private final Bootstrap bootstrap;
    private final BootstrapProperties properties;
    private final Counter failures;
    private final AtomicBoolean completed = new AtomicBoolean();
    private Thread worker;
    private volatile boolean stopped;

    public BootstrapRunner(Bootstrap bootstrap, BootstrapProperties properties, MeterRegistry registry) {
        this.bootstrap = bootstrap;
        this.properties = properties;
        failures = registry.counter("libra.analytics.bootstrap.failures");
        Gauge.builder("libra.analytics.bootstrap.completed", completed, value -> value.get() ? 1 : 0)
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (worker != null || stopped || !properties.enabled()) { return; }
        worker = new Thread(this::run, "analytics-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        long delay = 5_000;
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try {
                var report = bootstrap.run(properties.generation(), properties.pageSize());
                completed.set(report.completed());
                return;
            } catch (Exception exception) {
                if (stopped || Thread.currentThread().isInterrupted()) { return; }
                failures.increment();
                // A snapshot failure carries a fixed code (CORE_STATUS, CORE_BODY, ...); other messages are never logged.
                LOG.warn("Bootstrap attempt failed: {}", exception instanceof CoreSnapshots.SnapshotUnavailableException
                        ? exception.getMessage() : exception.getClass().getSimpleName());
                try { Thread.sleep(delay); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delay = Math.min(60_000, delay * 2);
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    public synchronized void stop() {
        stopped = true;
        if (worker != null) { worker.interrupt(); }
    }
}
