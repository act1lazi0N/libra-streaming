package com.libra.streaming.core.integration.media;

import org.apache.kafka.clients.producer.ProducerRecord;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class MediaConsumerConfiguration {
    @Bean
    org.springframework.kafka.support.ProducerListener<Object, Object> integrationProducerListener(
            io.micrometer.core.instrument.MeterRegistry metrics) {
        return new org.springframework.kafka.support.ProducerListener<>() {
            @Override public void onError(ProducerRecord<Object, Object> record,
                    org.apache.kafka.clients.producer.RecordMetadata metadata, Exception exception) {
                // The default producer listener logs record values, including private DLT payloads.
                metrics.counter("libra.integration.kafka.errors").increment();
            }
        };
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> mediaListenerFactory(
            ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> producer) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new DefaultErrorHandler((record, exception) -> {
            // A new record drops untrusted headers and raw exception text/stack traces.
            var dead = new ProducerRecord<String, String>("media.assets.v1.DLT", record.partition(),
                    (String) record.key(), (String) record.value());
            dead.headers().add(MediaDeadLetterStore.ORIGINAL_TOPIC, record.topic().getBytes(StandardCharsets.UTF_8));
            dead.headers().add(MediaDeadLetterStore.ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(record.partition()).array());
            dead.headers().add(MediaDeadLetterStore.ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(record.offset()).array());
            try { producer.send(dead).get(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("DLT delivery interrupted");
            } catch (Exception failure) { throw new IllegalStateException("DLT delivery failed"); }
        }, new FixedBackOff(1000L, 2L)));
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> mediaDeadLetterFactory(ConsumerFactory<String, String> consumers) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Durable capture failures must never be recovered by logging/skipping, nor recurse into another DLT.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, Long.MAX_VALUE)));
        return factory;
    }
}
