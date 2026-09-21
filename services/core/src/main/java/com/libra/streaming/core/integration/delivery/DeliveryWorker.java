package com.libra.streaming.core.integration.delivery;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeliveryWorker {
    private final DeliveryStore store;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry metrics;
    private final boolean enabled;
    public DeliveryWorker(DeliveryStore store, KafkaTemplate<String, String> kafka, MeterRegistry metrics,
            @Value("${libra.integration.publisher-enabled:true}") boolean enabled) {
        this.store = store; this.kafka = kafka; this.metrics = metrics; this.enabled = enabled;
    }
    @Scheduled(fixedDelayString = "${libra.integration.publisher-delay-ms:1000}")
    public void drain() {
        if (!enabled) { return; }
        for (int i = 0; i < 10; i++) {
            boolean worked = publishNext(DeliveryStore.Kind.OUTBOX);
            worked |= publishNext(DeliveryStore.Kind.MEDIA_REDRIVE);
            if (!worked || Thread.currentThread().isInterrupted()) { return; }
        }
    }
    public boolean publishNext(DeliveryStore.Kind kind) {
        var candidate = store.claim(kind);
        if (candidate.isEmpty()) { return false; }
        var job = candidate.get();
        try {
            kafka.send(job.topic(), job.key(), job.body()).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); store.failed(job); count(kind, "failed"); return true;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException exception) {
            store.failed(job); count(kind, "failed"); return true;
        }
        // Deliberately outside the send catch: a failed DB mark leaves the lease for recovery/replay.
        count(kind, store.acknowledged(job) ? "acknowledged" : "fenced");
        return true;
    }
    private void count(DeliveryStore.Kind kind, String outcome) {
        metrics.counter("libra.integration.delivery", "kind", kind.name(), "outcome", outcome).increment();
    }
}
