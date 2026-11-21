package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * One consumer group per Core topic, so each topic's progress (and later its freshness) is tracked on its own.
 * The container acknowledges a record only when this method returns, which is after the intake transaction
 * committed (AckMode.RECORD); every failure leaves the offset where it is.
 */
@Component
public class CoreEventListener {
    private final EventIntake intake;
    private final MeterRegistry metrics;

    public CoreEventListener(EventIntake intake, MeterRegistry metrics) {
        this.intake = intake;
        this.metrics = metrics;
    }

    @KafkaListener(id = "analytics-catalog", topics = "core.catalog.v1", groupId = InputTopic.Groups.CATALOG,
            containerFactory = "analyticsListenerFactory", autoStartup = "${libra.analytics.consumers.enabled:true}")
    public void catalog(ConsumerRecord<String, String> record) { receive(InputTopic.CATALOG, record); }

    @KafkaListener(id = "analytics-profiles", topics = "core.profiles.v1", groupId = InputTopic.Groups.PROFILES,
            containerFactory = "analyticsListenerFactory", autoStartup = "${libra.analytics.consumers.enabled:true}")
    public void profiles(ConsumerRecord<String, String> record) { receive(InputTopic.PROFILES, record); }

    @KafkaListener(id = "analytics-playback", topics = "core.playback.v1", groupId = InputTopic.Groups.PLAYBACK,
            containerFactory = "analyticsListenerFactory", autoStartup = "${libra.analytics.consumers.enabled:true}")
    public void playback(ConsumerRecord<String, String> record) { receive(InputTopic.PLAYBACK, record); }

    void receive(InputTopic topic, ConsumerRecord<String, String> record) {
        if (!topic.wireName().equals(record.topic())) { throw new IllegalStateException("Unexpected topic"); }
        EventIntake.Outcome outcome;
        try { outcome = intake.accept(topic, record.key(), record.value()); }
        catch (EventRejection rejection) {
            count(topic, "rejected");
            throw rejection;
        } catch (RuntimeException failure) {
            // Never pass SQL, driver or parser text to the container's error logging.
            if (Failures.isTransient(failure)) { throw new Failures.TransientIntakeException(); }
            throw new Failures.IntakeFailedException();
        }
        count(topic, outcome == EventIntake.Outcome.APPLIED ? "applied" : "duplicate");
    }

    private void count(InputTopic topic, String outcome) {
        metrics.counter("libra.analytics.events", "topic", topic.wireName(), "outcome", outcome).increment();
    }
}
