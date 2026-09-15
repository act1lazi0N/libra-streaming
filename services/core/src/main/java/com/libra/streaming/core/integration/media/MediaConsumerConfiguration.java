package com.libra.streaming.core.integration.media;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class MediaConsumerConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> mediaListenerFactory(
            ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> producer) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        var recoverer = new DeadLetterPublishingRecoverer(producer,
                (record, exception) -> new TopicPartition("media.assets.v1.DLT", record.partition()));
        // Never acknowledge poison input if durable dead-letter publication failed.
        recoverer.setFailIfSendResultIsError(true);
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L)));
        return factory;
    }
}
